/*
 * Copyright 2020 the original author or authors.
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
package org.gradle.api.internal.std;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.initialization.ProjectDescriptor;
import org.gradle.api.initialization.Settings;
import org.gradle.api.initialization.dsl.DependenciesModelBuilder;
import org.gradle.api.internal.ClassPathRegistry;
import org.gradle.api.internal.FeaturePreviews;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.internal.artifacts.DefaultProjectDependencyFactory;
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectRegistry;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.provider.Property;
import org.gradle.initialization.DependenciesAccessors;
import org.gradle.internal.Cast;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.internal.execution.ExecutionEngine;
import org.gradle.internal.execution.ExecutionEngine2;
import org.gradle.internal.execution.InputChangesContext;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.history.changes.InputChangesInternal;
import org.gradle.internal.execution.impl.DefaultExecutionEngine2;
import org.gradle.internal.file.TreeType;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.classpath.ClasspathFingerprinter;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.management.DependenciesModelBuilderInternal;
import org.gradle.internal.management.DependencyResolutionManagementInternal;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.snapshot.ValueSnapshot;
import org.gradle.util.IncubationLogger;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.gradle.api.internal.std.SimpleGeneratedJavaClassCompiler.compile;

public class DefaultDependenciesAccessors implements DependenciesAccessors {
    private final static Logger LOGGER = Logging.getLogger(DefaultDependenciesAccessors.class);

    private final static String KEBAB_CASE = "[a-z]([a-z0-9\\-])*";
    private final static Pattern KEBAB_PATTERN = Pattern.compile(KEBAB_CASE);
    private final static String ACCESSORS_PACKAGE = "org.gradle.accessors.dm";
    private final static String ACCESSORS_CLASSNAME_PREFIX = "LibrariesFor";
    private final static String ROOT_PROJECT_ACCESSOR_FQCN = ACCESSORS_PACKAGE + "." + RootProjectAccessorSourceGenerator.ROOT_PROJECT_ACCESSOR_CLASSNAME;

    private final ClassPath classPath;
    private final DependenciesAccessorsWorkspace workspace;
    private final DefaultProjectDependencyFactory projectDependencyFactory;
    private final FeaturePreviews featurePreviews;
    private final ExecutionEngine2 engine;
    private final FileCollectionFactory fileCollectionFactory;
    private final ClasspathFingerprinter fingerprinter;
    private final List<AllDependenciesModel> models = Lists.newArrayList();
    private final Map<String, Class<? extends ExternalModuleDependencyFactory>> factories = Maps.newHashMap();

    private ClassLoaderScope classLoaderScope;
    private Class<? extends TypeSafeProjectDependencyFactory> generatedProjectFactory;
    private ClassPath sources = DefaultClassPath.of();
    private ClassPath classes = DefaultClassPath.of();

    public DefaultDependenciesAccessors(ClassPathRegistry registry,
                                        DependenciesAccessorsWorkspace workspace,
                                        DefaultProjectDependencyFactory projectDependencyFactory,
                                        FeaturePreviews featurePreview,
                                        ExecutionEngine engine,
                                        FileCollectionFactory fileCollectionFactory,
                                        ClasspathFingerprinter fingerprinter,
                                        InstantiatorFactory instantiatorFactory,
                                        ServiceRegistry serviceRegistry) {
        this.classPath = registry.getClassPath("DEPENDENCIES-EXTENSION-COMPILER");
        this.workspace = workspace;
        this.projectDependencyFactory = projectDependencyFactory;
        this.featurePreviews = featurePreview;
        this.engine = new DefaultExecutionEngine2(engine, instantiatorFactory, serviceRegistry);
        this.fileCollectionFactory = fileCollectionFactory;
        this.fingerprinter = fingerprinter;
    }

    @Override
    public void generateAccessors(List<DependenciesModelBuilder> builders, ClassLoaderScope classLoaderScope, Settings settings) {
        try {
            this.classLoaderScope = classLoaderScope;
            this.models.clear(); // this is used in tests only, shouldn't happen in real context
            for (DependenciesModelBuilder builder : builders) {
                AllDependenciesModel model = ((DependenciesModelBuilderInternal) builder).build();
                models.add(model);
            }
            if (models.stream().anyMatch(AllDependenciesModel::isNotEmpty)) {
                IncubationLogger.incubatingFeatureUsed("Type-safe dependency accessors");
                for (AllDependenciesModel model : models) {
                    if (model.isNotEmpty()) {
                        writeDependenciesAccessors(model);
                    }
                }
            }
            if (featurePreviews.isFeatureEnabled(FeaturePreviews.Feature.TYPESAFE_PROJECT_ACCESSORS)) {
                IncubationLogger.incubatingFeatureUsed("Type-safe project accessors");
                writeProjectAccessors(((SettingsInternal) settings).getProjectRegistry());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void writeDependenciesAccessors(AllDependenciesModel model) {
        engine.prepare()
            .workspace(workspace)
            .workUnit(DependencyAccessorUnitOfWork.class, params -> {
                params.getClassPath().set(classPath);
                params.getModel().set(model);
            }).executeNow().getExecutionResult().ifSuccessful(er -> {
                GeneratedAccessors accessors = (GeneratedAccessors) er.getOutput();
                ClassPath generatedClasses = DefaultClassPath.of(accessors.classesDir);
                sources = sources.plus(DefaultClassPath.of(accessors.sourcesDir));
                classes = classes.plus(generatedClasses);
                classLoaderScope.export(generatedClasses);
        });
    }

    private void writeProjectAccessors(ProjectRegistry<? extends ProjectDescriptor> projectRegistry) {
        if (!assertCanGenerateAccessors(projectRegistry)) {
            return;
        }
    }

    private static boolean assertCanGenerateAccessors(ProjectRegistry<? extends ProjectDescriptor> projectRegistry) {
        List<String> errors = Lists.newArrayList();
        projectRegistry.getAllProjects()
            .stream()
            .map(ProjectDescriptor::getName)
            .filter(p -> !KEBAB_PATTERN.matcher(p).matches())
            .map(name -> "project '" + name + "' doesn't follow the kebab case naming convention: " + KEBAB_CASE)
            .forEach(errors::add);
        for (ProjectDescriptor project : projectRegistry.getAllProjects()) {
            project.getChildren()
                .stream()
                .map(ProjectDescriptor::getName)
                .collect(Collectors.groupingBy(AbstractSourceGenerator::toJavaName))
                .entrySet()
                .stream()
                .filter(e -> e.getValue().size() > 1)
                .forEachOrdered(e -> {
                    String javaName = e.getKey();
                    List<String> names = e.getValue();
                    errors.add("subprojects " + names + " of project " + project.getPath() + " map to the same method name get" + javaName + "()");
                });
        }
        if (!errors.isEmpty()) {
            for (String error : errors) {
                LOGGER.warn("Cannot generate project dependency accessors because " + error);
            }
        }
        return errors.isEmpty();
    }

    @Nullable
    private <T> Class<? extends T> loadFactory(ClassLoaderScope classLoaderScope, String className) {
        Class<? extends T> clazz;
        try {
            clazz = Cast.uncheckedCast(classLoaderScope.getExportClassLoader().loadClass(className));
        } catch (ClassNotFoundException e) {
            return null;
        }
        return clazz;
    }

    @Override
    public void createExtensions(ProjectInternal project) {
        ExtensionContainer container = project.getExtensions();
        try {
            if (!models.isEmpty()) {
                for (AllDependenciesModel model : models) {
                    if (model.isNotEmpty()) {
                        Class<? extends ExternalModuleDependencyFactory> factory;
                        synchronized (this) {
                            factory = factories.computeIfAbsent(model.getName(), n -> loadFactory(classLoaderScope, ACCESSORS_PACKAGE + "." + ACCESSORS_CLASSNAME_PREFIX + StringUtils.capitalize(n)));
                        }
                        if (factory != null) {
                            container.create(model.getName(), factory, model);
                        }
                    }
                }
            }
        } finally {
            if (featurePreviews.isFeatureEnabled(FeaturePreviews.Feature.TYPESAFE_PROJECT_ACCESSORS)) {
                ServiceRegistry services = project.getServices();
                DependencyResolutionManagementInternal drm = services.get(DependencyResolutionManagementInternal.class);
                ProjectFinder projectFinder = services.get(ProjectFinder.class);
                createProjectsExtension(container, drm, projectFinder);
            }
        }
    }

    private void createProjectsExtension(ExtensionContainer container, DependencyResolutionManagementInternal drm, ProjectFinder projectFinder) {
        if (generatedProjectFactory == null) {
            synchronized (this) {
                generatedProjectFactory = loadFactory(classLoaderScope, ROOT_PROJECT_ACCESSOR_FQCN);
            }
        }
        if (generatedProjectFactory != null) {
            Property<String> defaultProjectsExtensionName = drm.getDefaultProjectsExtensionName();
            defaultProjectsExtensionName.finalizeValue();
            container.create(defaultProjectsExtensionName.get(), generatedProjectFactory, projectDependencyFactory, projectFinder);
        }
    }

    @Override
    public ClassPath getSources() {
        return sources;
    }

    @Override
    public ClassPath getClasses() {
        return classes;
    }

    private static abstract class AbstractAccessorUnitOfWork implements UnitOfWork {
        private static final String OUT_SOURCES = "sources";
        private static final String OUT_CLASSES = "classes";
        public static final String DEPENDENCY_ACCESSORS_PREFIX = "dependency-accessors/";

        public interface AccessorParams extends ExecutionEngine2.ConfigurableUnitOfWork.Params {
            Property<ClassPath> getClassPath();
        }

        @Inject
        public abstract FileCollectionFactory getFileCollectionFactory();

        @Override
        public Identity identify(Map<String, ValueSnapshot> identityInputs, Map<String, CurrentFileCollectionFingerprint> identityFileInputs) {
            return () -> {
                Hasher hasher = Hashing.sha1().newHasher();
                identityInputs.values().forEach(s -> s.appendToHasher(hasher));
                return hasher.hash().toString();
            };
        }

        protected abstract List<ClassSource> getClassSources();

        abstract AccessorParams getParams();

        @Override
        public WorkOutput execute(@Nullable InputChangesInternal inputChanges, InputChangesContext context) {
            File workspace = context.getWorkspace();
            File srcDir = new File(workspace, OUT_SOURCES);
            File dstDir = new File(workspace, OUT_CLASSES);
            List<ClassSource> sources = getClassSources();
            ClassPath classPath = getParams().getClassPath().get();
            compile(srcDir, dstDir, sources, classPath);
            return new WorkOutput() {
                @Override
                public WorkResult getDidWork() {
                    return WorkResult.DID_WORK;
                }

                @Override
                public Object getOutput() {
                    return loadRestoredOutput(workspace);
                }
            };
        }

        @Override
        public Object loadRestoredOutput(File workspace) {
            File srcDir = new File(workspace, OUT_SOURCES);
            File dstDir = new File(workspace, OUT_CLASSES);
            return new GeneratedAccessors(srcDir, dstDir);
        }

        @Override
        public void visitOutputs(File workspace, OutputVisitor visitor) {
            File srcDir = new File(workspace, OUT_SOURCES);
            File dstDir = new File(workspace, OUT_CLASSES);
            visitOutputDir(visitor, srcDir, OUT_SOURCES);
            visitOutputDir(visitor, dstDir, OUT_CLASSES);
        }

        private void visitOutputDir(OutputVisitor visitor, File dir, String propertyName) {
            visitor.visitOutputProperty(propertyName, TreeType.DIRECTORY, dir, getFileCollectionFactory().fixed(dir));
        }
    }

    public abstract static class DependencyAccessorUnitOfWork extends AbstractAccessorUnitOfWork implements ExecutionEngine2.ConfigurableUnitOfWork<DependencyAccessorUnitOfWork.DependencyAccessorParams> {
        private static final String IN_DEPENDENCY_ALIASES = "dependencyAliases";
        private static final String IN_BUNDLES = "bundles";
        private static final String IN_VERSIONS = "versions";
        private static final String IN_MODEL_NAME = "modelName";
        private static final String IN_CLASSPATH = "classpath";

        public interface DependencyAccessorParams extends AbstractAccessorUnitOfWork.AccessorParams {
            Property<AllDependenciesModel> getModel();
        }

        private AllDependenciesModel getModel() {
            return getParams().getModel().get();
        }

        @Inject
        public abstract ClasspathFingerprinter getFingerprinter();

        @Override
        protected List<ClassSource> getClassSources() {
            AllDependenciesModel model = getModel();
            return Collections.singletonList(new DependenciesAccessorClassSource(model.getName(), model));
        }

        @Override
        public void visitInputs(InputVisitor visitor) {
            AllDependenciesModel model = getModel();
            visitor.visitInputProperty(IN_DEPENDENCY_ALIASES, IdentityKind.IDENTITY, model::getDependencyAliases);
            visitor.visitInputProperty(IN_BUNDLES, IdentityKind.IDENTITY, model::getBundleAliases);
            visitor.visitInputProperty(IN_VERSIONS, IdentityKind.IDENTITY, model::getVersionAliases);
            visitor.visitInputProperty(IN_MODEL_NAME, IdentityKind.IDENTITY, model::getName);
            ClassPath classPath = getParams().getClassPath().get();
            visitor.visitInputFileProperty(IN_CLASSPATH, InputPropertyType.NON_INCREMENTAL, IdentityKind.IDENTITY, classPath, () -> getFingerprinter().fingerprint(getFileCollectionFactory().fixed(classPath.getAsFiles())));
        }

        @Override
        public String getDisplayName() {
            return "generation of dependency accessors for " + getModel().getName();
        }
    }

    private static class GeneratedAccessors {
        private final File sourcesDir;
        private final File classesDir;

        private GeneratedAccessors(File sourcesDir, File classesDir) {
            this.sourcesDir = sourcesDir;
            this.classesDir = classesDir;
        }
    }

    private static class DependenciesAccessorClassSource implements ClassSource {

        private final String name;
        private final AllDependenciesModel model;

        private DependenciesAccessorClassSource(String name, AllDependenciesModel model) {
            this.name = name;
            this.model = model;
        }

        @Override
        public String getPackageName() {
            return ACCESSORS_PACKAGE;
        }

        @Override
        public String getSimpleClassName() {
            return ACCESSORS_CLASSNAME_PREFIX + StringUtils.capitalize(name);
        }

        @Override
        public String getSource() {
            StringWriter writer = new StringWriter();
            DependenciesSourceGenerator.generateSource(writer, model, ACCESSORS_PACKAGE, getSimpleClassName());
            return writer.toString();
        }
    }

    private static class ProjectAccessorClassSource implements ClassSource {
        private final ProjectDescriptor project;
        private String className;
        private String source;

        private ProjectAccessorClassSource(ProjectDescriptor project) {
            this.project = project;
        }

        @Override
        public String getPackageName() {
            return ACCESSORS_PACKAGE;
        }

        @Override
        public String getSimpleClassName() {
            ensureInitialized();
            return className;
        }

        @Override
        public String getSource() {
            ensureInitialized();
            return source;
        }

        private void ensureInitialized() {
            if (className == null) {
                StringWriter writer = new StringWriter();
                className = ProjectAccessorsSourceGenerator.generateSource(writer, project, ACCESSORS_PACKAGE);
                source = writer.toString();
            }
        }
    }

    private static class RootProjectAccessorSource implements ClassSource {
        private final ProjectDescriptor rootProject;

        private RootProjectAccessorSource(ProjectDescriptor rootProject) {
            this.rootProject = rootProject;
        }

        @Override
        public String getPackageName() {
            return ACCESSORS_PACKAGE;
        }

        @Override
        public String getSimpleClassName() {
            return RootProjectAccessorSourceGenerator.ROOT_PROJECT_ACCESSOR_CLASSNAME;
        }

        @Override
        public String getSource() {
            StringWriter writer = new StringWriter();
            RootProjectAccessorSourceGenerator.generateSource(writer, rootProject, ACCESSORS_PACKAGE);
            return writer.toString();
        }

    }


}
