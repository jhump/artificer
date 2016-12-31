# Artificer
**Artificer** is a skilled craftsman that strives to make Java annotation objects easier to use. It invents APIs that complement your annotations. This is done during the annotation processing step of `javac`, making it easy to incorporate into your builds.

**Artificer** can currently manufacture [Builders](#builders) and [Bridges](#bridges) for your annotations.

## Builders
The [builder pattern](https://en.wikipedia.org/wiki/Builder_pattern) is often used to construct value objects with an API that is much more readable than constructors with long argument lists.

Annotations, at heart, are value types. They represent values that are defined in source and (if their retention policy allows it) preserved in Java classes, for inspection at runtime via core reflection. But they are *interfaces*. No implementation is provided. So if you find yourself using them as value types, you'll find that synthesizing new instances at runtime can be quite a chore, involving lots of boiler-plate.

**Artificer** simplifies this by automatically generating builder classes for your annotations (and associated implementation classes). It's as easy as marking your annotation with `@HasBuilder`:

```java
@HasBuilder
@interface Foo {
	String bar();
	Class<? extends Frobitz>[] baz() default {}
}
```

Here is simple code to create a new instance of the annotation defined above:

```java
Foo foo1 = new Foo$Builder()
    .bar("Michael Bluth")
    .addBaz(EphemeralFrobnitz.class)
    .addBaz(MutableFrobnitz.class)
    .build();
```

As can be seen above, the generated builder class has the same package and name as the annotation, except it has a `$Builder` suffix.

Builder classes created by **Artificer** also make it easy to modify annotation values, computing a new object with just one (or more) values updated:

```java
// copies foo1, and just changes bar
Foo foo2 = new Foo$Builder(foo1)
    .bar("Tobias Fünke")
    .build();
```

When using a builder, the only fields that must be set are those that do not have default values. For example, the `baz` method on annotation `Foo` has a default which will be used if not otherwise set via the builder:

```java
Foo foo3 = new Foo$Builder().bar("Bob Loblaw").build();
assert foo3.baz().length == 0; // default value was defined as empty array
```

This expression, on the other hand, throws an `IllegalStateException` because `bar` is not set and has no default:

```java
Foo foo4 = new Foo$Builder().build();
```

Most fields on the annotation are defined via simple setter methods, that have the same name as the field itself and accept a parameter of the same type. But fields that are array types get a little extra API -- both setter and adder methods:

```java
// Uses a setter, which removes/replaces any values previously defined
Foo foo5 = new Foo$Builder()
    .bar("Franklin Delano Bluth")
    .baz(EncodingDecodingFrobnitz.class, AbstractFrobnitz.class)
    .build();
// Uses adder to create copy of foo5 with third element in baz
Foo foo6 = new Foo$Builder(foo5).addBaz(HyperbaricFrobnitz.class).build();
```

Setters for fields with array types are overloaded to accept either an array (can use var-args, as shown in the example above) or a `List`.

*Note*: If a field with an array type has a default, then using an adder method without prior use of a setter will be adding elements to the default array contents.

If a field with an array type does *not* have a default, then using an adder method will initialize the field to an empty array before adding the given element.

## Bridges
Annotation bridges provide a parallel API for easily using your annotation *at compile time*, inside of an annotation processor. At compile time, it isn't always possible to create annotation instances because they may refer to types that are not yet known or only exist in *source* form because `javac` hasn't yet compiled them.

When writing annotation processors, this is solved by using `AnnotationMirror` objects. These allow your code to interact with the source form of an annotation. But the API is very general and can be very hard to use.

Let's consider this simple annotation:
```java
@interface Foo {
    String bar();
    Class<? extends Frobitz>[] baz() default {}
}
```

Say we wanted to validate the format of the `bar` string in an annotation processor. Given a mirror, extracting values can be a bit of a hassle. There are a couple of approaches:

1. Create an index so code can easily lookup the annotation's `ExecutableElement` given a name. This allows code to easily find the right key for the `bar` string field, for example.
2. Scan the map of values each time code needs to extract a particular value, searching for the key with the right name.

Here are some code examples:
```java
//---------------------------------------------------------------------
// Here's the former approach:
//---------------------------------------------------------------------

Map<String, ExecutableElement> fooFields = new HashMap<>();
// the map is an index, for easily looking up elements by name
for (Element enclosed
        : (TypeElement) fooMirror.getAnnotationType().asElement()) {
    if (enclosed.getKind() != ElementKind.METHOD) {
        continue;
    }
    ExecutableElement ex = (ExecutableElement) enclosed;
    if (ex.getModifiers().contains(Modifier.STATIC)
            || !ex.getParameters().isEmpty()) {
        continue;
    }
    fooFields.put(ex.getSimpleName().toString(), ex);
}

// Using the index, we can now lookup values by name
AnnotationValue v = fooMirror.getElementValues()
        .get(fooFields.get("bar"));
if (v == null) {
    // could have a default value
    v = fooFields.get("bar").getDefaultValue();
}

// Now we have an AnnotationValue for the field of interest!

//---------------------------------------------------------------------
// Here's the latter approach:
//---------------------------------------------------------------------

Map<? extends ExecutableElement, ? extends AnnotationValue> vals;
vals = processingEnv.getElementUtils()
        .getElementValuesWithDefaults(fooMirror);
AnnotationValue v = null;
for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
        : vals.entrySet()) {
    ExecutableElement key = entry.getKey();
    if (key.getSimpleName().contentEquals("bar")) {
        v = entry.getValue();
        break;
    }
}
assert v != null;

// Now we have an AnnotationValue for the field of interest!
```

Once we've extracted an `AnnotationValue`, we're still not done. Next we have to extract the actual underlying data value. For code that a priori knows the structure of the annotation, the actual value can be simply cast to the expected type and then used. More complicated processing may need to instead use an `AnnotationValueVisitor`. Luckily, what we're trying to do (validate the format of the `bar` field for our `Foo` annotation) means we can do the former:
```java
// The resulting object will be a String, a boxed primitive, an AnnotationMirror,
// a TypeMirror (for a DeclaredType), or a VariableElement for scalar fields. If
// the field is defined to return an array, the resulting object will be a List of
// AnnotationValue objects (from which we can extract the right kind of value for
// the expected array contents).
String bar = (String) v.getValue();
```

The `AnnotationMirror` API is quite powerful. But ease-of-use is not at all a strong point.

Enter annotation bridges. They help "bridge the gap" between your annotation's API and that of an `AnnotationMirror`. With a bridge, we can use basically the same API as the original annotation inside our annotation processor:
```java
Foo$Bridge foo = new Foo$Bridge(fooMirror);
String bar = foo.bar();
```

To have **Artificer** create bridges for your annotations, simply mark them as `@Bridged`:

```java
@Bridged
@interface Foo {
    String bar();
    Class<? extends Frobitz>[] baz() default {}
}
```

Generated bridge classes have the same name and package as your annotation, but with a `$Bridge` suffix. Their API is identical to that of your annotation with just a few simple transformations:

1. Methods in your annotation that return `Class` tokens instead return `TypeElement` objects in the bridge. This is because referenced classes may not be representable as class tokens (e.g. they aren't yet compiled and loadable by the JVM).
3. Methods in your annotation that return other annotation values will instead return *other bridges*.
4. Methods in your annotation that return arrays will instead return `List` objects. The type of element in the list follows these same rules. So a method that returned an array of `Class` tokens will have a bridge method that returns `List<TypeElement>`. Arrays of primitive types will be bridged via lists of their boxed counterparts, for example `int[]` in an annotation will be `List<Integer>` in the bridge.

## Custom Meta-Annotations
In addition to the meta-annotations `@HasBuilder` and `@Bridged`, you can create your own meta-annotations that trigger **Artificer** to action. Simple mark your meta-annotation as a `@BuilderMarker` and/or a `@BridgeMarker`.

```java
// Any annotation that is marked wth MySpecialMarker
// will have a builder and a bridge generated for it!
@BuilderMarker
@BridgeMarker
@interface MySpecialMarker {
}
```