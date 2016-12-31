package com.bluegosling.artificer.internal;

import com.bluegosling.artificer.builders.Builder;
import com.bluegosling.artificer.builders.BuilderMarker;
import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.auto.service.AutoService;
import com.google.common.base.Throwables;
//import com.google.googlejavaformat.java.Formatter;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;

import java.io.BufferedWriter;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;

import javax.annotation.processing.Processor;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;

/**
 * A processor that generates a builder class to accompany annotations indirectly marked with the
 * meta-meta-annotation {@link BuilderMarker}, as well as any annotations nested therein. The
 * generated class has the same name as the source annotation but with a "$Builder" suffix.
 */
@AutoService(Processor.class)
public class AnnotationBuilderProcessor extends AbstractMetaMetaProcessor {
   private static final String BUILDER_NAME_SUFFIX = "$Builder";
   private static final String IMPL_NAME_SUFFIX = "$Impl";

   @Override
   public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
   }

   @Override
   protected Class<? extends Annotation> metaMetaAnnotation() {
      return BuilderMarker.class;
   }

   @Override
   protected void processAnnotation(TypeElement annotation) {
      try {
         BuilderGenerator generator = new BuilderGenerator(annotation);
         JavaFile javaFile = generator.generate();
         JavaFileObject outputFile = processingEnv.getFiler().createSourceFile(
               generator.packageName + "." + generator.annotationName + BUILDER_NAME_SUFFIX);
         
         try (Writer writer = new BufferedWriter(outputFile.openWriter())) {
            javaFile.writeTo(writer);
            //writer.write(new Formatter().formatSource(javaFile.toString()));
         }
      } catch (Exception e) {
         processingEnv.getMessager().printMessage(Kind.ERROR, Throwables.getStackTraceAsString(e));
      }
   }

   /**
    * Generates a builder class (with enclosed implementation class) for a given annotation.
    */
   private class BuilderGenerator {
      // the annotation, for which a builder is generated
      private final TypeElement annotation;
      private final String packageName;
      private final String annotationName;
      private final TypeName annotationType;

      // the generated builder class
      private TypeSpec.Builder builder;
      private TypeName builderType;

      // the generated implementation class
      private TypeSpec.Builder impl;

      // code blocks which accumulate per-method statements
      private CodeBlock.Builder buildValidate;
      private CodeBlock.Builder builderDefaultCtorInitializer;
      private CodeBlock.Builder builderCopyCtorInitializer;
      private CodeBlock.Builder implCtorInitializer;
      private CodeBlock.Builder equalsImpl;
      private CodeBlock.Builder hashCodeImpl;
      private CodeBlock.Builder toStringImpl;

      BuilderGenerator(TypeElement annotation) {
         this.annotation = annotation;
         this.packageName = getPackageName(annotation);
         this.annotationType = TypeName.get(annotation.asType());

         // Get simple name for the annotation. If it's a nested type, dots become dollars in the
         // generated class names: e.g. Outer.Inner produces Outer$Inner.
         this.annotationName = typeSimpleName(
               processingEnv.getElementUtils().getBinaryName(annotation).toString(), packageName);
      }

      /**
       * Runs the generator and returns the resulting Java file.
       */
      public JavaFile generate() {
         builderType = ClassName.get(packageName, annotationName + BUILDER_NAME_SUFFIX);

         builder = TypeSpec.classBuilder(annotationName + BUILDER_NAME_SUFFIX)
               .addModifiers(Modifier.PUBLIC)
               .addAnnotation(generatedAnnotation(annotation))
               .superclass(ParameterizedTypeName.get(ClassName.get(Builder.class), annotationType))
               .addJavadoc("A builder for creating new instances of {@link $T} annotations.",
                     annotationType);

         impl = TypeSpec.classBuilder(annotationName + IMPL_NAME_SUFFIX)
               .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
               .addSuperinterface(annotationType)
               .addMethod(MethodSpec.methodBuilder("annotationType")
                     .addAnnotation(Override.class)
                     .addModifiers(Modifier.PUBLIC)
                     .returns(ParameterizedTypeName.get(ClassName.get(Class.class), annotationType))
                     .addStatement("return $T.class", annotationType)
                     .build());

         // we accumulate numerous code blocks that have per-method code all in a single sweep
         // over the annotation's methods
         buildValidate = CodeBlock.builder();
         builderDefaultCtorInitializer = CodeBlock.builder();
         builderCopyCtorInitializer = CodeBlock.builder();
         implCtorInitializer = CodeBlock.builder();
         equalsImpl = CodeBlock.builder();
         hashCodeImpl = CodeBlock.builder();
         toStringImpl = CodeBlock.builder();

         boolean first = true;

         // process each annotation method
         for (Element e : annotation.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD && !e.getModifiers().contains(Modifier.STATIC)) {
               ExecutableElement method = MoreElements.asExecutable(e);

               if (first) {
                  first = false;
                  equalsImpl.add("return ");
                  hashCodeImpl.add("return ");
               } else {
                  equalsImpl.add("\n    && ");
                  hashCodeImpl.add("\n    + ");
                  toStringImpl.addStatement("sb.append(\",\")");
               }

               new MethodProcessor(method).process();
            }
         }

         // after processing all methods, we can now generate non-method-specific code
         generateBuilderMethods();
         generateImplMethods();

         // nest the impl inside of the builder
         builder.addType(impl.build());

         // BOOM! done
         return JavaFile.builder(packageName, builder.build()).build();
      }

      private void generateBuilderMethods() {
         builder.addMethod(MethodSpec.methodBuilder("build")
               .addAnnotation(Override.class)
               .addModifiers(Modifier.PUBLIC)
               .returns(annotationType)
               .addCode(buildValidate.build())
               .addStatement("return new $L$L(this)", annotationName, IMPL_NAME_SUFFIX)
               .addJavadoc("Builds an instance of {@link $T} annotation using the values provided.\n"
                     + "Any fields that have no default value must be provided before calling this\n"
                     + "method.\n"
                     + "\n"
                     + "@return a new annotation instance\n"
                     + "@throws IllegalStateException if any fields have no default value and have\n"
                     + "not been provided", annotationType)
               .build());

         // copy constructor; goes from impl to builder
         builder.addMethod(MethodSpec.constructorBuilder()
               .addModifiers(Modifier.PUBLIC)
               .addParameter(annotationType, "a")
               .addCode(builderCopyCtorInitializer.build())
               .addJavadoc("Creates a new builder where all values are initialized according to the\n"
                     + "given {@link $T} annotation instance.", annotationType)
               .build());

         // also need an explicit no-arg constructor
         builder.addMethod(MethodSpec.constructorBuilder()
               .addModifiers(Modifier.PUBLIC)
               .addCode(builderDefaultCtorInitializer.build())
               .addJavadoc("Creates a new builder.")
               .build());
      }

      private void generateImplMethods() {
         impl.addMethod(MethodSpec.methodBuilder("equals")
               .addAnnotation(Override.class)
               .addModifiers(Modifier.PUBLIC)
               .returns(boolean.class)
               .addParameter(Object.class, "o")
               .addStatement("if (this == o) return true")
               .addStatement("if (!(o instanceof $T)) return false", annotationType)
               .addStatement("$T other = ($T) o", annotationType, annotationType)
               .addStatement("if (other.annotationType() != $T.class) return false", annotationType)
               .addCode(equalsImpl.add(";\n").build())
               .build());

         impl.addMethod(MethodSpec.methodBuilder("hashCode")
               .addAnnotation(Override.class)
               .addModifiers(Modifier.PUBLIC)
               .returns(int.class)
               .addCode(hashCodeImpl.add(";\n").build())
               .build());

         impl.addMethod(MethodSpec.methodBuilder("toString")
               .addAnnotation(Override.class)
               .addModifiers(Modifier.PUBLIC)
               .returns(String.class)
               .addStatement("StringBuilder sb = new StringBuilder()")
               .addStatement("sb.append(\"@\").append($T.class.getCanonicalName()).append(\"(\")",
                     annotationType)
               .addCode(toStringImpl.build())
               .addStatement("sb.append(\")\")")
               .addStatement("return sb.toString()")
               .build());

         impl.addMethod(MethodSpec.constructorBuilder()
               .addParameter(builderType, "b")
               .addCode(implCtorInitializer.build())
               .build());
      }

      /**
       * Processes a single method on the annotation. Each method results in fields and methods on
       * the generated builder and implementation class.
       */
      private class MethodProcessor {
         private final ExecutableElement method;
         private final String methodName;
         private final TypeMirror methodTypeMirror;
         private final TypeName methodType;
         private final AnnotationValue defaultValue;

         // can vary from methodType, e.g. list instead of array
         private final TypeName builderFieldType;

         // non-null if method type is an array
         private final TypeMirror componentTypeMirror;
         private final TypeName componentType;

         MethodProcessor(ExecutableElement method) {
            this.method = method;

            // We use the annotation method name extensively. Accessor and setter methods in the
            // implementation and builder classes, as well as associated fields and method
            // parameters, are all named after it.
            this.methodName = method.getSimpleName().toString();

            this.methodTypeMirror = method.getReturnType();
            this.methodType = TypeName.get(methodTypeMirror);
            this.defaultValue = method.getDefaultValue();

            if (methodTypeMirror.getKind() == TypeKind.ARRAY) {
               this.componentTypeMirror = ((ArrayType) methodTypeMirror).getComponentType();
               this.componentType = TypeName.get(componentTypeMirror);
               // use list instead of array in builder type, for easily accumulating elements
               builderFieldType = ParameterizedTypeName.get(ClassName.get(List.class),
                     TypeName.get(box(componentTypeMirror)));
            } else {
               this.componentTypeMirror = null;
               this.componentType = null;
               if (defaultValue == null) {
                  // primitive values without a default are represented as boxed types
                  // (null means never set)
                  builderFieldType = TypeName.get(box(methodTypeMirror));
               } else {
                  builderFieldType = methodType;
               }
            }
         }

         /** Generates code related to the current annotation method. */
         public void process() {
            // if the method's return type is also an annotation we should produce a builder for it,
            // too
            TypeElement methodTypeElement = getMethodTypeElement(methodTypeMirror);
            if (methodTypeElement != null
                  && methodTypeElement.getKind() == ElementKind.ANNOTATION_TYPE
                  && !builderExists(methodTypeElement)) {
               enqueueAnnotation(methodTypeElement, method);
            }

            generateBuilderCode();
            generateImplCode();
         }

         /** Returns true if the current method's return type is an array. */
         private boolean isArray() {
            return componentTypeMirror != null;
         }

         private void generateBuilderCode() {
            // Field declaration
            builder.addField(
                  FieldSpec.builder(builderFieldType, methodName, Modifier.PRIVATE).build());

            // Field initialization in default constructor
            if (defaultValue != null) {
               builderDefaultCtorInitializer.add("this.$L = ", methodName);
               asLiteral(defaultValue, methodTypeMirror, builderDefaultCtorInitializer);
               builderDefaultCtorInitializer.add(";\n");
            }

            // Setter method(s)
            generateSetters();

            // Code block to validate that required elements were set in build() method
            if (defaultValue == null) {
               buildValidate.beginControlFlow("if ($L == null)", methodName)
                     .addStatement("throw new $T($S)", IllegalStateException.class,
                           methodName + " cannot be null")
                     .endControlFlow();
            }

            // Code block to initialize field in copy constructor
            // (variable a is an instance of the annotation)
            if (methodTypeMirror.getKind().isPrimitive()) {
               builderCopyCtorInitializer.addStatement("this.$L = a.$L()", methodName, methodName);
            } else {
               // For reference types, we perform a null check. Annotations aren't ever supposed to
               // return null values, so this is just in case we encounter a misbehaving
               // implementation
               // of the annotation interface
               builderCopyCtorInitializer
                     .addStatement("$T __tmp$L = a.$L()", methodType, methodName, methodName)
                     .beginControlFlow("if (__tmp$L == null)", methodName)
                     .addStatement("throw new $T($S)", NullPointerException.class, methodName)
                     .endControlFlow();
               if (isArray()) {
                  builderCopyCtorInitializer
                        .addStatement("this.$L = new $T<>(__tmp$L.length)", methodName,
                              ArrayList.class, methodName)
                        .beginControlFlow("for ($T __item : __tmp$L)", componentType, methodName);
                  if (!componentTypeMirror.getKind().isPrimitive()) {
                     // also null-check each element of reference array
                     builderCopyCtorInitializer.beginControlFlow("if (__item == null)")
                           .addStatement("throw new $T($S)", NullPointerException.class, methodName)
                           .endControlFlow();
                  }
                  builderCopyCtorInitializer.addStatement("this.$L.add(__item)", methodName)
                        .endControlFlow();
               } else {
                  builderCopyCtorInitializer.addStatement("this.$L = __tmp$L", methodName,
                        methodName);
               }
            }
         }

         private void generateSetters() {
            // All fields get a simple setter method
            MethodSpec.Builder methodBuilder =
                  MethodSpec.methodBuilder(methodName)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(builderType)
                        .addParameter(methodType, methodName)
                        .addJavadoc("Provides a value for the {@link $T#$L() $L} field.",
                              annotationType, methodName, methodName);
            if (isArray()) {
               methodBuilder.varargs();
               if (componentType.isPrimitive()) {
                  // defensive copy and adapt from primitive array to boxed list
                  methodBuilder
                        .addStatement("this.$L = new $T<>($L.length)", methodName, ArrayList.class,
                              methodName)
                        .beginControlFlow("for ($T __item : $L)", componentType, methodName)
                        .addStatement("this.$L.add(__item)", methodName)
                        .endControlFlow();

               } else {
                  // validate incoming values and then copy
                  methodBuilder.beginControlFlow("for ($T __item : $L)", componentType, methodName)
                        .beginControlFlow("if (__item == null)")
                        .addStatement("throw new $T($S)", NullPointerException.class, methodName)
                        .endControlFlow()
                        .endControlFlow();
                  methodBuilder.addStatement("this.$L = new $T<>($T.asList($L))", methodName,
                        ArrayList.class, Arrays.class, methodName);
               }
            } else {
               if (!methodType.isPrimitive()) {
                  methodBuilder.beginControlFlow("if ($L == null)", methodName)
                        .addStatement("throw new $T($S)", NullPointerException.class, methodName)
                        .endControlFlow();
               }
               methodBuilder.addStatement("this.$L = $L", methodName, methodName);
            }
            methodBuilder.addStatement("return this");
            builder.addMethod(methodBuilder.build());

            // List types have additional methods:

            if (isArray()) {
               // Overload to accept collection parameter (vs. array parameter)
               TypeName boxedComponent = TypeName.get(box(componentTypeMirror));
               TypeName overloadParamType =
                     ParameterizedTypeName.get(ClassName.get(Collection.class),
                              WildcardTypeName.subtypeOf(boxedComponent));
               methodBuilder = MethodSpec.methodBuilder(methodName)
                     .addModifiers(Modifier.PUBLIC)
                     .returns(builderType)
                     .addParameter(overloadParamType, methodName)
                     .addJavadoc("Provides a value for the {@link $T#$L() $L} field using a "
                           + "collection instead of an array.", annotationType, methodName,
                           methodName);
               // validate incoming values
               methodBuilder.beginControlFlow("for ($T __item : $L)", boxedComponent, methodName)
                     .beginControlFlow("if (__item == null)")
                     .addStatement("throw new $T($S)", NullPointerException.class, methodName)
                     .endControlFlow()
                     .endControlFlow();
               builder.addMethod(methodBuilder
                     .addStatement("this.$L = new $T<>($L)", methodName,
                           ArrayList.class, methodName)
                     .addStatement("return this")
                     .build());

               // Method for incrementally adding one value at a time
               MethodSpec.Builder addMethod = MethodSpec.methodBuilder("add" + initCap(methodName))
                     .addModifiers(Modifier.PUBLIC)
                     .returns(builderType)
                     .addParameter(componentType, methodName)
                     .addJavadoc("Appends an element to the array value for the {@link $T#$L() $L} "
                           + "field.", annotationType, methodName, methodName);
               if (!componentType.isPrimitive()) {
                  addMethod.beginControlFlow("if ($L == null)", methodName)
                        .addStatement("throw new $T($S)", NullPointerException.class, methodName)
                        .endControlFlow();
               }
               if (defaultValue == null) {
                  // no default means field could be uninitialized (e.g. null), so init if necessary
                  addMethod.beginControlFlow("if (this.$L == null)", methodName)
                        .addStatement("this.$L = new $T<>()", methodName, ArrayList.class)
                        .endControlFlow();
               }
               addMethod.addStatement("this.$L.add($L)", methodName, methodName)
                     .addStatement("return this");
               builder.addMethod(addMethod.build());
            }
         }

         private void generateImplCode() {
            // Field declaration
            impl.addField(methodType, methodName, Modifier.PRIVATE, Modifier.FINAL);

            // Accessor method (implements annotation interface)
            impl.addMethod(
                  MethodSpec.methodBuilder(methodName)
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(methodType)
                        .addStatement("return $L"
                              + (methodTypeMirror.getKind() == TypeKind.ARRAY ? ".clone()" : ""),
                              methodName)
                        .build());

            // Code block to initialize field in implementation class's constructor
            // (variable b is a builder)
            if (!isArray()) {
               implCtorInitializer.addStatement("this.$L = b.$L", methodName, methodName);
            } else if (componentTypeMirror.getKind().isPrimitive()) {
               // must adapt from boxed list to array of primitives
               implCtorInitializer
                     .addStatement("$T __tmp$L = new $T[b.$L.size()]", methodType, methodName,
                           componentType, methodName)
                     .beginControlFlow("for (int __index = 0; __index < __tmp$L.length; __index++)",
                           methodName)
                     .addStatement("__tmp$L[__index] = b.$L.get(__index)", methodName, methodName)
                     .endControlFlow()
                     .addStatement("this.$L = __tmp$L", methodName, methodName);
            } else {
               TypeMirror rawType = rawComponentType(componentTypeMirror);
               if (rawType != componentTypeMirror) {
                  // must use raw array type and then unchecked-cast
                  implCtorInitializer.add("@$T($S)\n", SuppressWarnings.class, "unchecked")
                        .addStatement("$T __tmp$L = ($T) b.$L.toArray(new $T[0])",
                              methodType, methodName, methodType, methodName,
                              TypeName.get(rawType))
                        .addStatement("this.$L = __tmp$L", methodName, methodName);
               } else {
                  implCtorInitializer.addStatement("this.$L = b.$L.toArray(new $T[0])",
                        methodName, methodName, componentType);
               }
            }

            // equals, hashCode, and toString:

            addEquals(methodName, methodTypeMirror, equalsImpl);

            hashCodeImpl.add("(127 * $S.hashCode() ^ ", methodName);
            addHashCode(methodName, methodTypeMirror, hashCodeImpl);
            hashCodeImpl.add(")");

            toStringImpl.addStatement("sb.append($S)", methodName + "=");
            addToString(methodName, methodTypeMirror, toStringImpl);
         }

         private TypeElement getMethodTypeElement(TypeMirror mirror) {
            if (mirror.getKind() == TypeKind.ARRAY) {
               return getMethodTypeElement(((ArrayType) mirror).getComponentType());
            } else if (mirror.getKind() == TypeKind.DECLARED) {
               return MoreElements.asType(((DeclaredType) mirror).asElement());
            } else {
               return null;
            }
         }

         private String initCap(String s) {
            if (s.isEmpty() || Character.isUpperCase(s.charAt(0))) {
               return s;
            }
            StringBuilder sb = new StringBuilder(s);
            sb.setCharAt(0, Character.toUpperCase(s.charAt(0)));
            return sb.toString();
         }

         /**
          * Emits the given value to the given code block using a form suitable for constructing
          * that value when executed.
          */
         private void asLiteral(AnnotationValue v, TypeMirror t, CodeBlock.Builder block) {
            asLiteral(v, t, block, false);
         }

         private void asLiteral(AnnotationValue v, TypeMirror t, CodeBlock.Builder block,
               boolean acceptVarArgs) {
            Object value = v.getValue();
            if (value instanceof TypeMirror) {

               // Class token
               block.add("$T.class", TypeName.get((TypeMirror) value));

            } else if (value instanceof AnnotationMirror) {

               // Nested annotation (use a builder to instantiate)
               AnnotationMirror a = (AnnotationMirror) value;
               block.add("new $T()", builderClassName(a.getAnnotationType()));
               for (Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : a
                     .getElementValues().entrySet()) {
                  block.add(".$L(", entry.getKey().getSimpleName());
                  asLiteral(entry.getValue(), entry.getKey().getReturnType(), block, true);
                  block.add(")");
               }
               block.add(".build()");

            } else if (value instanceof VariableElement) {

               // Enum
               VariableElement enumField = (VariableElement) value;
               block.add("$T.$L",
                     TypeName.get(MoreElements.asType(enumField.getEnclosingElement()).asType()),
                     enumField.getSimpleName());

            } else if (value instanceof List) {

               // Array
               List<?> list = (List<?>) value;
               TypeMirror componentType = ((ArrayType) t).getComponentType();
               if (list.isEmpty()) {
                  // if var args, we can just emit no elements...
                  if (!acceptVarArgs) {
                     block.add("$T.<$T>emptyList()", Collections.class, box(componentType));
                  }
               } else {
                  if (!acceptVarArgs) {
                     // no var args? wrap the items in a new list
                     block.add("new $T<>($T.<$T>asList(", ArrayList.class, Arrays.class,
                           box(componentType));
                  }

                  boolean first = true;
                  for (Object o : (List<?>) value) {
                     if (first) {
                        first = false;
                     } else {
                        block.add(",");
                     }
                     asLiteral((AnnotationValue) o, componentType, block);
                  }

                  if (!acceptVarArgs) {
                     block.add("))");
                  }
               }

            } else {

               // Strings and primitives
               block.add(processingEnv.getElementUtils().getConstantExpression(value));

            }
         }

         /**
          * Returns the raw type corresponding to the given mirror. If the given type is not
          * generic, it is returned. Otherwise, all type arguments are stripped.
          */
         private TypeMirror rawComponentType(TypeMirror t) {
            // We don't need to check for TypeKind.ARRAY and recurse because annotation values can
            // only have one-dimensional array types.
            return t.getKind() == TypeKind.DECLARED ? rawComponentType((DeclaredType) t) : t;
         }

         private DeclaredType rawComponentType(DeclaredType t) {
            // recreate the declared type, but without any type args
            TypeMirror owner = t.getEnclosingType();
            TypeMirror rawOwner = rawComponentType(owner);
            List<? extends TypeMirror> args = t.getTypeArguments();
            if (owner == rawOwner && args.isEmpty()) {
               // not a generic type
               return t;
            }

            assert rawOwner.getKind() == TypeKind.DECLARED || rawOwner.getKind() == TypeKind.NONE;

            return rawOwner.getKind() == TypeKind.DECLARED
                  ? processingEnv.getTypeUtils().getDeclaredType((DeclaredType) rawOwner,
                        (TypeElement) t.asElement())
                  : processingEnv.getTypeUtils().getDeclaredType((TypeElement) t.asElement());
         }

         /**
          * Emits a portion of the {@link #equals} method's {@code return} statement. The portion
          * just compares the given annotation method.
          */
         private void addEquals(String methodName, TypeMirror methodTypeMirror,
               CodeBlock.Builder equalsImpl) {
            switch (methodTypeMirror.getKind()) {
               case DECLARED:
                  equalsImpl.add("$L.equals(other.$L())", methodName, methodName);
                  break;
               case FLOAT:
                  equalsImpl.add("$T.valueOf($L).equals($T.valueOf(other.$L()))", Float.class,
                        methodName, Float.class, methodName);
                  break;
               case DOUBLE:
                  equalsImpl.add("$T.valueOf($L).equals($T.valueOf(other.$L()))", Double.class,
                        methodName, Double.class, methodName);
                  break;
               case ARRAY:
                  equalsImpl.add("$T.equals($L, other.$L())", Arrays.class, methodName, methodName);
                  break;
               default:
                  equalsImpl.add("$L == other.$L()", methodName, methodName);
                  break;
            }
         }

         /**
          * Emits a portion of the {@link #hashCode} method's {@code return} statement. The portion
          * just get the hash code contribution for the given annotation method.
          */
         private void addHashCode(String methodName, TypeMirror methodTypeMirror,
               CodeBlock.Builder hashCodeImpl) {
            if (methodTypeMirror.getKind().isPrimitive()) {
               Class<?> boxedType = boxClass((PrimitiveType) methodTypeMirror);
               hashCodeImpl.add("$T.valueOf($L).hashCode()", boxedType, methodName);
            } else if (methodTypeMirror.getKind() == TypeKind.ARRAY) {
               hashCodeImpl.add("$T.hashCode($L)", Arrays.class, methodName);
            } else {
               hashCodeImpl.add("$L.hashCode()", methodName);
            }
         }

         /**
          * Emits statements to the {@link #toString} method's implementation. These invoke methods
          * on a {@link StringBuilder} variable named {@code sb}.
          *
          * <p>The generated {@link #toString} method emits a string that is also a valid
          * representation of the annotation in source code.
          */
         private void addToString(String variableName, TypeMirror typeMirror,
               CodeBlock.Builder toStringImpl) {
            if (typeMirror.getKind() == TypeKind.CHAR) {
               toStringImpl.beginControlFlow("if ($L == '\\'')", variableName)
                     .addStatement("sb.append(\"'\\\\''\")")
                     .endControlFlow()
                     .beginControlFlow("else")
                     .addStatement("sb.append('\\'').append($L).append('\\'')", variableName)
                     .endControlFlow();
            } else if (typeMirror.getKind().isPrimitive()) {
               toStringImpl.addStatement("sb.append($L)", variableName);
            } else if (typeMirror.getKind() == TypeKind.ARRAY) {
               toStringImpl.addStatement("sb.append(\"{\")");
               TypeMirror componentType = ((ArrayType) typeMirror).getComponentType();
               toStringImpl.beginControlFlow("for ($T i : $L)", componentType, variableName);
               addToString("i", componentType, toStringImpl);
               toStringImpl.addStatement("sb.append(',')");
               toStringImpl.endControlFlow();
               toStringImpl.addStatement("sb.append(\"}\")");
            } else {
               TypeElement element = MoreTypes.asTypeElement(typeMirror);
               switch (element.getKind()) {
                  case ENUM:
                     toStringImpl
                           .addStatement(
                                 "sb.append($L.getDeclaringClass().getCanonicalName())"
                                       + ".append(\".\").append($L.name())",
                                 variableName, variableName);
                     break;
                  case ANNOTATION_TYPE:
                     toStringImpl.addStatement("sb.append($L.toString())", variableName);
                     break;
                  default:
                     if (element.getQualifiedName().toString().equals("java.lang.Class")) {
                        toStringImpl.addStatement(
                              "sb.append($L.getCanonicalName()).append(\".class\")", variableName);
                     } else if (element.getQualifiedName().toString().equals("java.lang.String")) {
                        // escape strings
                        toStringImpl.addStatement(
                              "sb.append('\"').append($L.replace(\"\\\"\", \"\\\\\\\"\")).append('\"')",
                              variableName);
                     } else {
                        throw new AssertionError(
                              "Unsupported type in annotation! " + element.getQualifiedName());
                     }
               }
            }
         }
      }
   }

   /**
    * Returns a type mirror for the boxed type that corresponds to the given type. If the given
    * type is not a primitive type, it is returned unchanged. Otherwise, its boxed reference type
    * is returned.
    */
   private TypeMirror box(TypeMirror type) {
      return type.getKind().isPrimitive()
            ? processingEnv.getTypeUtils().boxedClass((PrimitiveType) type).asType() : type;
   }

   /** Returns the class token for the box type that corresponds to the given primitive type. */
   private static Class<?> boxClass(PrimitiveType typeMirror) {
      switch (typeMirror.getKind()) {
         case BOOLEAN:
            return Boolean.class;
         case BYTE:
            return Byte.class;
         case SHORT:
            return Short.class;
         case CHAR:
            return Character.class;
         case INT:
            return Integer.class;
         case LONG:
            return Long.class;
         case FLOAT:
            return Float.class;
         case DOUBLE:
            return Double.class;
         default:
            throw new AssertionError(
                  "Unsupported type kind in annotation! " + typeMirror.getKind().name());
      }
   }

   /**
    * Returns the name of the builder that would be generated for the given annotation type.
    */
   private ClassName builderClassName(DeclaredType annotationType) {
      TypeElement element = MoreElements.asType(annotationType.asElement());
      String packageName = getPackageName(element);
      String annotationName = typeSimpleName(
            processingEnv.getElementUtils().getBinaryName(element).toString(), packageName);
      return ClassName.get(packageName, annotationName + BUILDER_NAME_SUFFIX);
   }

   /**
    * Returns true if a builder for the given annotation already exists.
    */
   private boolean builderExists(TypeElement type) {
      String builderName =
            processingEnv.getElementUtils().getBinaryName(type) + BUILDER_NAME_SUFFIX;
      return processingEnv.getElementUtils().getTypeElement(builderName) != null;
   }

   /**
    * Returns the simple name of the class with the given binary name. This effectively just strips
    * off the given package prefix. For nested classes, this will return a name that uses dollar
    * signs ($) to separate nested from enclosing class names.
    */
   private static String typeSimpleName(String typeBinaryName, String packageName) {
      if (!packageName.isEmpty()) {
         assert typeBinaryName.startsWith(packageName + ".");
         typeBinaryName = typeBinaryName.substring(packageName.length() + 1);
      }
      return typeBinaryName;
   }

   /**
    * Returns the name of the package for the given type.
    */
   private static String getPackageName(TypeElement type) {
      return MoreElements.getPackage(type).getQualifiedName().toString();
   }
}
