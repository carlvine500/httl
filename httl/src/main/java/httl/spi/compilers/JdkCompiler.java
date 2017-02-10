/*
 * Copyright 2011-2013 HTTL Team.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package httl.spi.compilers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import httl.spi.Compiler;
import httl.util.ClassUtils;
import httl.util.StringUtils;
import httl.util.UnsafeByteArrayInputStream;
import httl.util.UnsafeByteArrayOutputStream;

/**
 * JdkCompiler. (SPI, Singleton, ThreadSafe)
 *
 * @author Liang Fei (liangfei0201 AT gmail DOT com)
 * @author Adamansky Anton (adamansky@softmotions.com)
 * @see httl.spi.translators.CompiledTranslator#setCompiler(Compiler)
 */
public class JdkCompiler extends AbstractCompiler {

    private final JavaCompiler compiler;

    private final StandardJavaFileManager standardJavaFileManager;

    private final ClassLoader parentClassLoader;

    private final JavaFileManagerImpl javaFileManager;

    // qualifiedClassName => TemplateClassLoader
    private final Map<String, TemplateClassLoader> qname2Loader = new HashMap<>();

    private final List<String> options = new ArrayList<>();

    private final List<String> lintOptions = new ArrayList<>();

    private boolean lintUnchecked;

    private static final Pattern CLASS_TS_REGEXP = Pattern.compile("_ts(\\d+)?");

    public JdkCompiler() {
        compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new
                    IllegalStateException("Can not get system java compiler. " +
                                          "Please run with JDK (NOT JVM), or configure the httl.properties: " +
                                          "compiler=httl.spi.compilers.JavassistCompiler, and add javassist.jar.");
        }
        standardJavaFileManager = compiler.getStandardFileManager(new DiagnosticListener<JavaFileObject>() {
            @Override
            public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
                switch (diagnostic.getKind()) {
                    case ERROR:
                        logger.error(diagnostic.toString());
                        break;
                    case MANDATORY_WARNING:
                    case WARNING:
                    case NOTE:
                    case OTHER:
                        if (logger.isDebugEnabled() || logger.isTraceEnabled()) {
                            logger.debug(diagnostic.getKind().toString() + ' ' + diagnostic.toString());
                        }
                        break;
                }
            }
        }, null, null);
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        try {
            contextLoader.loadClass(JdkCompiler.class.getName());
        } catch (ClassNotFoundException ignored) { // 如果线程上下文的ClassLoader不能加载当前httl.jar包中的类，则切换回httl.jar所在的ClassLoader
            contextLoader = JdkCompiler.class.getClassLoader();
        }
        this.parentClassLoader = contextLoader;

        ClassLoader loader = contextLoader;
        Set<File> files = new HashSet<>();
        while (loader instanceof URLClassLoader
               && (!"sun.misc.Launcher$AppClassLoader".equals(loader.getClass().getName()))) {
            URLClassLoader urlClassLoader = (URLClassLoader) loader;
            for (URL url : urlClassLoader.getURLs()) {
                files.add(new File(url.getFile()));
            }
            loader = loader.getParent();
        }
        if (!files.isEmpty()) {
            try {
                Iterable<? extends File> list = standardJavaFileManager.getLocation(StandardLocation.CLASS_PATH);
                for (File file : list) {
                    files.add(file);
                }
                standardJavaFileManager.setLocation(StandardLocation.CLASS_PATH, files);
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        javaFileManager = new JavaFileManagerImpl(standardJavaFileManager);
        lintOptions.add("-Xlint:unchecked");
    }

    public void init() {
        if (logger != null && logger.isDebugEnabled()) {
            StringBuilder buf = new StringBuilder(320);
            buf.append("JDK Compiler classpath locations:\n");
            buf.append("================\n");
            for (File file : standardJavaFileManager.getLocation(StandardLocation.CLASS_PATH)) {
                buf.append(file.getAbsolutePath());
                buf.append("\n");
            }
            buf.append("================\n");
            logger.debug(buf.toString());
        }
    }

    private String stripClassTimestamp(String cname) {
        Matcher m = CLASS_TS_REGEXP.matcher(cname);
        if (m.find()) {
            return cname.substring(0, cname.lastIndexOf('_'));
        } else {
            return cname;
        }
    }

    /**
     * httl.properties: java.specification.version=1.7
     */
    public void setCompileVersion(String version) {
        if (StringUtils.isNotEmpty(version)
            && !version.equals(ClassUtils.getJavaVersion())) {
            options.add("-target");
            options.add(version);
            lintOptions.add("-target");
            lintOptions.add(version);
        }
    }

    /**
     * httl.properties: lint.unchecked=true
     */
    public void setLintUnchecked(boolean lintUnchecked) {
        this.lintUnchecked = lintUnchecked;
    }

    @Override
    protected Class<?> doCompile(String name, String sourceCode) throws Exception {
        try {
            return doCompile(name, sourceCode, options);
        } catch (Exception e) {
            if (lintUnchecked && e.getMessage() != null
                && e.getMessage().contains("-Xlint:unchecked")) {
                return doCompile(name, sourceCode, lintOptions);
            }
            throw e;
        }
    }

    private Class<?> doCompile(String name, String sourceCode, List<String> options) throws Exception {

        String strippedName = stripClassTimestamp(name);
        // to avoid ts associated leaks we use one classloader per class
        TemplateClassLoader cl = qname2Loader.get(strippedName);
        if (cl != null && name.equals(cl.qualifiedClassName)) {
            try {
                return cl.loadClass(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        int i = name.lastIndexOf('.');
        String packageName = i < 0 ? "" : name.substring(0, i);
        String className = i < 0 ? name : name.substring(i + 1);
        JavaFileObjectImpl javaFileObject = new JavaFileObjectImpl(className, sourceCode);
        javaFileManager.putFileForInput(StandardLocation.SOURCE_PATH, packageName, className, javaFileObject);
        DiagnosticCollector<JavaFileObject> dc = new DiagnosticCollector<>();
        Boolean result = compiler.getTask(null, javaFileManager, dc, options,
                                          null, Collections.singletonList(javaFileObject)).call();
        if (result == null || !result) {
            throw new IllegalStateException("Compilation failed. class: " + name +
                                            ", diagnostics: " + dc.getDiagnostics());
        }
        cl = qname2Loader.get(strippedName);
        if (cl == null) {
            throw new IllegalStateException("Classloader for: " + name + " is not found");
        }
        return cl.loadClass(name);
    }

    private class TemplateClassLoader extends ClassLoader {

        private final JavaFileObjectImpl jfo;

        private final String qualifiedClassName;

        private TemplateClassLoader(String qualifiedClassName, Kind kind) {
            super(parentClassLoader);
            this.qualifiedClassName = qualifiedClassName;
            this.jfo = new JavaFileObjectImpl(qualifiedClassName, kind);
        }

        @Override
        protected Class<?> findClass(String qualifiedClassName) throws ClassNotFoundException {
            try {
                return super.findClass(qualifiedClassName);
            } catch (ClassNotFoundException e) {
                byte[] bytes = jfo.getByteCode();
                try {
                    saveBytecode(qualifiedClassName, bytes);
                } catch (IOException e2) {
                    throw new IllegalStateException(e2.getMessage(), e2);
                }
                return defineClass(qualifiedClassName, bytes, 0, bytes.length);
            }
        }

        @Override
        public InputStream getResourceAsStream(final String name) {
            if (name.endsWith(ClassUtils.CLASS_EXTENSION)) {
                String cn = name.substring(0, name.length() - ClassUtils.CLASS_EXTENSION.length()).replace('/', '.');
                TemplateClassLoader slot = qname2Loader.get(cn);
                if (slot == null) {
                    slot = qname2Loader.get(stripClassTimestamp(cn));
                }
                if (slot != null) {
                    return new UnsafeByteArrayInputStream(slot.jfo.getByteCode());
                }
            }
            return super.getResourceAsStream(name);
        }
    }

    private static final class JavaFileObjectImpl extends SimpleJavaFileObject {

        private UnsafeByteArrayOutputStream bytecode;

        private final CharSequence source;

        private JavaFileObjectImpl(final String baseName, final CharSequence source) {
            super(ClassUtils.toURI(baseName + ClassUtils.JAVA_EXTENSION), Kind.SOURCE);
            this.source = source;
        }

        JavaFileObjectImpl(final String name, final Kind kind) {
            super(ClassUtils.toURI(name), kind);
            source = null;
        }

        private JavaFileObjectImpl(URI uri, Kind kind) {
            super(uri, kind);
            source = null;
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) throws UnsupportedOperationException {
            if (source == null) {
                throw new UnsupportedOperationException("source == null");
            }
            return source;
        }

        @Override
        public InputStream openInputStream() {
            return new UnsafeByteArrayInputStream(getByteCode());
        }

        @Override
        public OutputStream openOutputStream() {
            return bytecode = new UnsafeByteArrayOutputStream();
        }

        public byte[] getByteCode() {
            return bytecode.toByteArray();
        }
    }

    private class JavaFileManagerImpl extends ForwardingJavaFileManager<JavaFileManager> {

        private final Map<URI, JavaFileObject> fileObjects = new HashMap<>();

        private JavaFileManagerImpl(JavaFileManager fileManager) {
            super(fileManager);
        }

        private URI uri(JavaFileManager.Location location, String packageName, String relativeName) {
            return ClassUtils.toURI(location.getName() + '/' + packageName + '/' + stripClassTimestamp(relativeName));
        }

        public void putFileForInput(StandardLocation location,
                                    String packageName,
                                    String className,
                                    JavaFileObject file) {
            fileObjects.put(uri(location, packageName,
                                stripClassTimestamp(className) + ClassUtils.JAVA_EXTENSION),
                            file);
        }

        @Override
        public FileObject getFileForInput(Location location,
                                          String packageName,
                                          String relativeName) throws IOException {
            if (relativeName.endsWith(ClassUtils.JAVA_EXTENSION)) {
                relativeName = stripClassTimestamp(
                        relativeName.substring(0, relativeName.length() - ClassUtils.JAVA_EXTENSION.length())
                );
            }
            FileObject o = fileObjects.get(uri(location, packageName, relativeName));
            if (o != null) {
                return o;
            }
            return super.getFileForInput(location, packageName, relativeName);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location,
                                                   String qualifiedName,
                                                   Kind kind,
                                                   FileObject outputFile) throws IOException {
            TemplateClassLoader slot = new TemplateClassLoader(qualifiedName, kind);
            qname2Loader.put(stripClassTimestamp(qualifiedName), slot);
            return slot.jfo;
        }

        @Override
        public String inferBinaryName(Location loc, JavaFileObject file) {
            if (file instanceof JavaFileObjectImpl) {
                return file.getName();
            }
            return super.inferBinaryName(loc, file);
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse)
                throws IOException {
            List<JavaFileObject> files = new ArrayList<>();
            if (location == StandardLocation.CLASS_PATH && kinds.contains(JavaFileObject.Kind.CLASS)) {
                for (JavaFileObject file : fileObjects.values()) {
                    if (file.getKind() == Kind.CLASS && file.getName().startsWith(packageName)) {
                        files.add(file);
                    }
                }
                for (TemplateClassLoader ts : qname2Loader.values()) {
                    files.add(ts.jfo);
                }
            } else if (location == StandardLocation.SOURCE_PATH && kinds.contains(JavaFileObject.Kind.SOURCE)) {
                for (JavaFileObject file : fileObjects.values()) {
                    if (file.getKind() == Kind.SOURCE && file.getName().startsWith(packageName)) {
                        files.add(file);
                    }
                }
            }
            Iterable<JavaFileObject> result = super.list(location, packageName, kinds, recurse);
            for (JavaFileObject file : result) {
                files.add(file);
            }
            return files;
        }
    }
}