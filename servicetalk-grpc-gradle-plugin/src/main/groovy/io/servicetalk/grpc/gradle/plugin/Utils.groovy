/*
 * Copyright © 2020 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.grpc.gradle.plugin;

import org.apache.commons.lang.StringUtils
import org.gradle.api.tasks.SourceSet
import org.gradle.util.GUtil

final class Utils {
    private Utils() {
    }

    /**
     * Returns the conventional name of a configuration for a sourceSet
     */
    static String getConfigName(String sourceSetName, String type) {
        // same as DefaultSourceSet.configurationNameOf
        String baseName = sourceSetName == SourceSet.MAIN_SOURCE_SET_NAME ?
            '' : GUtil.toCamelCase(sourceSetName)
        return StringUtils.uncapitalize(baseName + StringUtils.capitalize(type))
    }

    /**
     * Returns the conventional substring that represents the sourceSet in task names,
     * e.g., "generate<sourceSetSubstring>Proto"
     */
    static String getSourceSetSubstringForTaskNames(String sourceSetName) {
        return sourceSetName == SourceSet.MAIN_SOURCE_SET_NAME ?
            '' : StringUtils.capitalize(sourceSetName)
    }

    static boolean isTest(String sourceSetOrVariantName) {
        return sourceSetOrVariantName == "test"
    }


}
