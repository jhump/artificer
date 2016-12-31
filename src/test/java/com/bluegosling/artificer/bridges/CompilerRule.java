package com.bluegosling.artificer.bridges;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject.Kind;

/**
 * A rule that will run each test case in the context of a compiler invocation.
 * 
 * @author Joshua Humphries (jhumphries131@gmail.com)
 */
public class CompilerRule implements TestRule {
   private static final ThreadLocal<Statement> currentStatement = new ThreadLocal<>();

   private final AtomicBoolean entered = new AtomicBoolean();
   private final boolean reentrant;
   private final Set<JavaFileObject> compilationUnits;
   private final Set<Class<? extends Annotation>> annotationsSupported;
   private final Set<FileObject> filesCreated = new LinkedHashSet<>();
   private ProcessingEnvironment processingEnv;
   private Set<TypeElement> annotations;
   private RoundEnvironment roundEnv;

   private CompilerRule(Builder b) {
      this.reentrant = b.reentrant;
      this.compilationUnits = b.compilationUnits;
      this.annotationsSupported = b.annotationsSupported;
   }
   
   public ProcessingEnvironment processingEnv() {
      return processingEnv;
   }
   
   public Set<TypeElement> annotations() {
      return annotations;
   }
   
   public RoundEnvironment roundEnv() {
      return roundEnv;
   }
   
   private void removeAllOutputs() {
      for (FileObject o : filesCreated) {
         o.delete();
      }
      filesCreated.clear();
   }
   
   @Override
   public Statement apply(final Statement base, Description description) {
      JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
      final StringWriter writer = new StringWriter();
      DiagnosticListener<JavaFileObject> diags = new DiagnosticListener<JavaFileObject>() {
         @Override
         public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            writer.write(diagnostic.getKind().name());
            if (diagnostic.getSource() != null) {
               writer.write(" at " + diagnostic.getSource().getName() + ":"
                     + diagnostic.getLineNumber() + "," + diagnostic.getColumnNumber());
            }
            writer.write(": " + diagnostic.getMessage(null) + "\n");
         }
      };
      final CompilationTask task = javac.getTask(writer,
            new FileManager(
                  javac.getStandardFileManager(diags, Locale.getDefault(), Charsets.UTF_8)),
            diags,
            Collections.<String>emptyList(),
            compilationUnits.isEmpty()
                  ? Arrays.asList("java.lang.Object")
                  : Collections.<String>emptyList(),
            compilationUnits);
      task.setProcessors(Arrays.asList(new Processor()));
      return new Statement() {
         @Override
         public void evaluate() throws Throwable {
            currentStatement.set(base);
            try {
               Boolean ret = task.call();
               if (ret == null || !ret) {
                  throw new RuntimeException("Compilation failed:\n" + writer.toString());
               }
            } finally {
               currentStatement.remove();
               removeAllOutputs();
            }
         }
      };
   }
   
   public static class Builder {
      private boolean reentrant;
      private Set<JavaFileObject> compilationUnits = new LinkedHashSet<>();
      private Set<Class<? extends Annotation>> annotationsSupported = new LinkedHashSet<>();
      private boolean needReset;
      
      private void maybeReset() {
         if (needReset) {
            compilationUnits = new LinkedHashSet<>(compilationUnits);
            annotationsSupported = new LinkedHashSet<>(annotationsSupported);
            needReset = false;
         }
      }
      
      public Builder reentrant(boolean reentrant) {
         this.reentrant = reentrant;
         return this;
      }
      
      public Builder addCompilationUnit(JavaFileObject compilationUnit) {
         maybeReset();
         compilationUnits.add(requireNonNull(compilationUnit));
         return this;
      }

      public Builder addCompilationUnit(final String fullClassName, final String content) {
         requireNonNull(content);
         URI uri = URI.create(fullClassName.replace('.', '/') + Kind.SOURCE.extension);
         final long lastModified = System.currentTimeMillis();
         
         return addCompilationUnit(new SimpleJavaFileObject(uri, Kind.SOURCE) {
            
            @Override public String getCharContent(boolean ignoreEncodingErrors) {
               return content;
            }
            
            @Override public InputStream openInputStream() throws IOException {
               return new ByteArrayInputStream(getCharContent(true).getBytes());
            }
            
            @Override public long getLastModified() {
               return lastModified;
            }
         });
      }

      public Builder addSupportedAnnotation(Class<? extends Annotation> annotationType) {
         maybeReset();
         annotationsSupported.add(requireNonNull(annotationType));
         return this;
      }
      
      public CompilerRule build() {
         needReset = true;
         return new CompilerRule(this);
      }
   }
   
   private class FileManager implements JavaFileManager {
      private final JavaFileManager delegate;
      
      FileManager(StandardJavaFileManager delegate) {
         this.delegate = delegate;
         // direct all outputs to a temporary directory
         File tempDir = Files.createTempDir();
         for (Location l : StandardLocation.values()) {
            if (l.isOutputLocation()) {
               try {
                  delegate.setLocation(l, Arrays.asList(tempDir));
               } catch (IOException e) {
                  throw new UncheckedIOException(e);
               }
            }
         }
      }
      
      @Override
      public int isSupportedOption(String option) {
         return delegate.isSupportedOption(option);
      }

      @Override
      public ClassLoader getClassLoader(Location location) {
         return delegate.getClassLoader(location);
      }

      @Override
      public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds,
            boolean recurse) throws IOException {
         return delegate.list(location, packageName, kinds, recurse);
      }

      @Override
      public String inferBinaryName(Location location, JavaFileObject file) {
         return delegate.inferBinaryName(location, file);
      }

      @Override
      public boolean isSameFile(FileObject a, FileObject b) {
         return delegate.isSameFile(a, b);
      }

      @Override
      public boolean handleOption(String current, Iterator<String> remaining) {
         return delegate.handleOption(current, remaining);
      }

      @Override
      public boolean hasLocation(Location location) {
         return delegate.hasLocation(location);
      }

      @Override
      public JavaFileObject getJavaFileForInput(Location location, String className, Kind kind)
            throws IOException {
         return delegate.getJavaFileForInput(location, className, kind);
      }

      @Override
      public JavaFileObject getJavaFileForOutput(Location location, String className, Kind kind,
            FileObject sibling) throws IOException {
         JavaFileObject f = delegate.getJavaFileForOutput(location, className, kind, sibling);
         filesCreated.add(f);
         return f;
      }

      @Override
      public FileObject getFileForInput(Location location, String packageName, String relativeName)
            throws IOException {
         return delegate.getFileForInput(location, packageName, relativeName);
      }

      @Override
      public FileObject getFileForOutput(Location location, String packageName, String relativeName,
            FileObject sibling) throws IOException {
         FileObject f = delegate.getFileForOutput(location, packageName, relativeName, sibling);
         filesCreated.add(f);
         return f;
      }

      @Override
      public void flush() throws IOException {
         delegate.flush();
      }

      @Override
      public void close() throws IOException {
         delegate.close();
      }
   }
   
   private class Processor extends AbstractProcessor {
      @Override
      public Set<String> getSupportedAnnotationTypes() {
         if (annotationsSupported.isEmpty()) {
            return Collections.singleton("*");
         }
         Set<String> supportedAnnotations =
               new LinkedHashSet<>(annotationsSupported.size() * 4 / 3);
         for (Class<? extends Annotation> annotationType : annotationsSupported) {
            supportedAnnotations.add(annotationType.getCanonicalName());
         }
         return supportedAnnotations;
      }

      @Override
      public SourceVersion getSupportedSourceVersion() {
         return SourceVersion.latestSupported();
      }

      @Override
      public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
         if (roundEnv.processingOver()) {
            return false;
         }
         if (entered.compareAndSet(false, true) || reentrant) {
            try {
               CompilerRule.this.processingEnv = processingEnv;
               CompilerRule.this.annotations = Collections.unmodifiableSet(annotations);
               CompilerRule.this.roundEnv = roundEnv;
               currentStatement.get().evaluate();
            } catch (RuntimeException | Error e) {
               throw e;
            } catch (Throwable th) {
               throw new RuntimeException(th);
            }
         }
         return false;
      }
   }
}