/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.internal.extensibility;

import groovy.lang.Closure;
import groovy.lang.MissingPropertyException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.internal.ConventionMapping;
import org.gradle.api.internal.HasConvention;
import org.gradle.api.internal.IConventionAware;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.Convention;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.internal.Cast;
import org.gradle.internal.reflect.JavaPropertyReflectionUtil;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.gradle.util.GUtil.uncheckedCall;

public class ConventionAwareHelper implements ConventionMapping, HasConvention {
    //prefix internal fields with _ so that they don't get into the way of propertyMissing()
    private final Convention _convention;
    private final IConventionAware _source;
    private final Set<String> _propertyNames;
    private final Map<String, MappedPropertyImpl> _mappings = new HashMap<String, MappedPropertyImpl>();

    public ConventionAwareHelper(IConventionAware source, Convention convention) {
        this._source = source;
        this._convention = convention;
        this._propertyNames = JavaPropertyReflectionUtil.propertyNames(source);
    }

    private MappedProperty map(String propertyName, MappedPropertyImpl mapping) {
        if (ArchiveTaskCompatHelper.forwardConventionMapping(_source, propertyName, mapping, _convention)) {
            return mapping;
        }

        if (!_propertyNames.contains(propertyName)) {
            throw new InvalidUserDataException(
                "You can't map a property that does not exist: propertyName=" + propertyName);
        }

        _mappings.put(propertyName, mapping);
        return mapping;
    }

    private static boolean isArchiveTask(Class<?> cls) {
        if (cls.getCanonicalName().equals("org.gradle.api.tasks.bundling.AbstractArchiveTask")) {
            return true;
        }

        Class<?> superclass = cls.getSuperclass();
        if (superclass != null) {
            return isArchiveTask(superclass);
        }

        return false;
    }


    private void reflectivelySetStringConvention(Object target, String propertyGetter, MappedPropertyImpl action) {
        try {
            @SuppressWarnings("unchecked")
            Property<String> archiveFileName = (Property<String>) target.getClass().getMethod(propertyGetter).invoke(target);
            @SuppressWarnings("unchecked")
            Task task = (Task) target;
            ProviderFactory providers = task.getProject().getProviders();
            Property<String> prop = task.getProject().getObjects().property(String.class);
            archiveFileName.convention(prop.convention(providers.provider(() -> (String) action.getValue(_convention, _source))));
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    private void reflectivelySetDestinationDirNameConvention(Object target, MappedPropertyImpl action) {
        try {
            @SuppressWarnings("unchecked")
            DirectoryProperty property = (DirectoryProperty) target.getClass().getMethod("getDestinationDirectory").invoke(target);
            @SuppressWarnings("unchecked")
            Task task = (Task) target;
            Project project = task.getProject();
            ProviderFactory providers = project.getProviders();
            ObjectFactory objects = project.getObjects();
            DirectoryProperty prop = objects.directoryProperty();
            // why there's DirectoryProperty.set(File) but no DirectoryProperty.convention(File)?
            File dest = (File) action.getValue(_convention, _source);
            prop.set(dest);
            property.convention(prop.get());
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public MappedProperty map(String propertyName, final Closure<?> value) {
        return map(propertyName, new MappedPropertyImpl() {
            @Override
            public Object doGetValue(Convention convention, IConventionAware conventionAwareObject) {
                switch (value.getMaximumNumberOfParameters()) {
                    case 0:
                        return value.call();
                    case 1:
                        return value.call(convention);
                    default:
                        return value.call(convention, conventionAwareObject);
                }
            }
        });
    }

    @Override
    public MappedProperty map(String propertyName, final Callable<?> value) {
        return map(propertyName, new MappedPropertyImpl() {
            @Override
            public Object doGetValue(Convention convention, IConventionAware conventionAwareObject) {
                return uncheckedCall(value);
            }
        });
    }

    public void propertyMissing(String name, Object value) {
        if (value instanceof Closure) {
            map(name, Cast.<Closure<?>>uncheckedNonnullCast(value));
        } else {
            throw new MissingPropertyException(name, getClass());
        }
    }

    @Override
    public <T> T getConventionValue(T actualValue, String propertyName, boolean isExplicitValue) {
        if (isExplicitValue) {
            return actualValue;
        }

        T returnValue = actualValue;
        if (_mappings.containsKey(propertyName)) {
            boolean useMapping = true;
            if (actualValue instanceof Collection && !((Collection<?>) actualValue).isEmpty()) {
                useMapping = false;
            } else if (actualValue instanceof Map && !((Map<?, ?>) actualValue).isEmpty()) {
                useMapping = false;
            }
            if (useMapping) {
                returnValue = Cast.uncheckedNonnullCast(_mappings.get(propertyName).getValue(_convention, _source));
            }
        }
        return returnValue;
    }

    @Override
    public Convention getConvention() {
        return _convention;
    }

    private static abstract class MappedPropertyImpl implements MappedProperty {
        private boolean haveValue;
        private boolean cache;
        private Object cachedValue;

        public Object getValue(Convention convention, IConventionAware conventionAwareObject) {
            if (!cache) {
                return doGetValue(convention, conventionAwareObject);
            }
            if (!haveValue) {
                cachedValue = doGetValue(convention, conventionAwareObject);
                haveValue = true;
            }
            return cachedValue;
        }

        @Override
        public void cache() {
            cache = true;
            cachedValue = null;
        }

        abstract Object doGetValue(Convention convention, IConventionAware conventionAwareObject);
    }

    /**
     * Forwards convention mapping defined for removed AbstractArchiveTask properties.
     */
    static class ArchiveTaskCompatHelper {
        private static final Map<String, String> REMOVED_STRING_PROPERTY_MAPPING = new HashMap<>();

        static {
            REMOVED_STRING_PROPERTY_MAPPING.put("archiveName", "getArchiveFileName");
            REMOVED_STRING_PROPERTY_MAPPING.put("baseName", "getArchiveBaseName");
            REMOVED_STRING_PROPERTY_MAPPING.put("appendix", "getArchiveAppendix");
            REMOVED_STRING_PROPERTY_MAPPING.put("version", "getArchiveVersion");
            REMOVED_STRING_PROPERTY_MAPPING.put("extension", "getArchiveExtension");
            REMOVED_STRING_PROPERTY_MAPPING.put("classifier", "getArchiveClassifier");
        }

        public static boolean forwardConventionMapping(IConventionAware source, String propertyName, MappedPropertyImpl mapping, Convention convention) {
            if (isArchiveTask(source.getClass())) {
                String propertyGetterMethodName = REMOVED_STRING_PROPERTY_MAPPING.get(propertyName);
                if (propertyGetterMethodName != null) {
                    reflectivelySetStringConvention(propertyGetterMethodName, mapping, convention, source);
                    return true;
                } else if (propertyName.equals("destinationDir")) {
                    reflectivelySetDirectoryConvention("getDestinationDirectory", mapping, convention, source);
                    return true;
                }
            }
            return false;
        }

        private static boolean isArchiveTask(Class<?> cls) {
            if (cls.getCanonicalName().equals("org.gradle.api.tasks.bundling.AbstractArchiveTask")) {
                return true;
            }

            Class<?> superclass = cls.getSuperclass();
            if (superclass != null) {
                return isArchiveTask(superclass);
            }

            return false;
        }

        private static void reflectivelySetStringConvention(String propertyGetterMethodName, MappedPropertyImpl action, Convention convention, IConventionAware source) {
            try {
                @SuppressWarnings("unchecked")
                Property<String> archiveFileName = (Property<String>) source.getClass().getMethod(propertyGetterMethodName).invoke(source);
                @SuppressWarnings("unchecked")
                Task task = (Task) source;
                ProviderFactory providers = task.getProject().getProviders();
                Property<String> prop = task.getProject().getObjects().property(String.class);
                archiveFileName.convention(prop.convention(providers.provider(() -> (String) action.getValue(convention, source))));
            } catch(Exception e) {
               throw new RuntimeException(e);
            }
        }

        private static void reflectivelySetDirectoryConvention(String propertyGetterMethodName, MappedPropertyImpl action, Convention convention, IConventionAware source) {
            try {
                @SuppressWarnings("unchecked")
                DirectoryProperty property = (DirectoryProperty) source.getClass().getMethod(propertyGetterMethodName).invoke(source);
                @SuppressWarnings("unchecked")
                Task task = (Task) source;
                Project project = task.getProject();
                ProviderFactory providers = project.getProviders();
                ObjectFactory objects = project.getObjects();
                DirectoryProperty directoryProperty = objects.directoryProperty();
                property.convention(directoryProperty.convention(providers.provider(() -> {
                    // there's no DirectoryProperty.convention(File)
                    File dest = (File) action.getValue(convention, source);
                    DirectoryProperty prop = objects.directoryProperty();
                    prop.set(dest);
                    return prop.get();
                })));
            } catch(Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
