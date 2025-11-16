// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("IO_FILE_USAGE")

package com.jetbrains.ls.imports.maven

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.platform.workspace.jps.entities.*
import com.intellij.platform.workspace.jps.entities.LibraryTableId.ProjectLibraryTableId
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.url.VirtualFileUrlManager
import com.jetbrains.ls.imports.api.WorkspaceEntitySource
import com.jetbrains.ls.imports.api.WorkspaceImportException
import com.jetbrains.ls.imports.api.WorkspaceImporter
import com.jetbrains.ls.imports.utils.toIntellijUri
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenConstants
import org.jetbrains.idea.maven.project.*
import org.jetbrains.idea.maven.server.MavenServerManager
import org.jetbrains.idea.maven.utils.MavenProcessCanceledException
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.KotlinFacetSettings
import org.jetbrains.kotlin.config.KotlinModuleKind
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.idea.workspaceModel.KotlinSettingsEntity
import org.jetbrains.kotlin.idea.workspaceModel.kotlinSettings
import org.jetbrains.kotlin.platform.IdePlatformKind
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.impl.CommonIdePlatformKind
import org.jetbrains.kotlin.platform.impl.JsIdePlatformKind
import org.jetbrains.kotlin.platform.impl.JvmIdePlatformKind
import java.io.File
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.exists

object MavenWorkspaceImporter : WorkspaceImporter {
    private const val KOTLIN_PLUGIN_GROUP_ID = "org.jetbrains.kotlin"
    private const val KOTLIN_PLUGIN_ARTIFACT_ID = "kotlin-maven-plugin"

    private val LOG = logger<MavenWorkspaceImporter>()

    override suspend fun importWorkspace(
        projectDirectory: Path,
        virtualFileUrlManager: VirtualFileUrlManager,
        onUnresolvedDependency: (String) -> Unit,
    ): MutableEntityStorage? {
        if (!isApplicableDirectory(projectDirectory)) return null

        LOG.info("Importing Maven project from: $projectDirectory")

        try {
            val storage = MutableEntityStorage.create()
            val entitySource = WorkspaceEntitySource(projectDirectory.toIntellijUri(virtualFileUrlManager))

            // Read Maven projects using IntelliJ's Maven API
            val mavenProjects = readMavenProjects(projectDirectory)

            if (mavenProjects.isEmpty()) {
                throw WorkspaceImportException(
                    "No Maven modules found in the project",
                    "Maven project reading returned no modules"
                )
            }

            val libs = mutableSetOf<String>()
            val libraryEntities = mutableMapOf<String, LibraryEntity>()

            // Process each Maven module
            for (mavenProject in mavenProjects) {
                LOG.info("Processing Maven module: ${mavenProject.mavenId}")

                // Create main and test modules
                for (isMain in arrayOf(true, false)) {
                    val moduleEntity = createModuleEntity(
                        mavenProject = mavenProject,
                        isMain = isMain,
                        entitySource = entitySource,
                        virtualFileUrlManager = virtualFileUrlManager,
                        storage = storage,
                        libs = libs,
                        libraryEntities = libraryEntities,
                        onUnresolvedDependency = onUnresolvedDependency
                    )

                    storage addEntity moduleEntity
                }
            }

            LOG.info("Successfully imported ${mavenProjects.size} Maven modules")
            return storage

        } catch (e: MavenProcessCanceledException) {
            throw WorkspaceImportException(
                "Maven import was cancelled",
                "Maven import cancelled: ${e.message}",
                e
            )
        } catch (e: Exception) {
            throw WorkspaceImportException(
                "Failed to import Maven project: ${e.message}",
                "Maven import failed for $projectDirectory",
                e
            )
        }
    }

    private fun createModuleEntity(
        mavenProject: MavenProject,
        isMain: Boolean,
        entitySource: WorkspaceEntitySource,
        virtualFileUrlManager: VirtualFileUrlManager,
        storage: MutableEntityStorage,
        libs: MutableSet<String>,
        libraryEntities: MutableMap<String, LibraryEntity>,
        onUnresolvedDependency: (String) -> Unit
    ): ModuleEntity {
        val moduleName = getModuleName(mavenProject, isMain)

        return ModuleEntity(
            name = moduleName,
            dependencies = buildModuleDependencies(
                mavenProject = mavenProject,
                isMain = isMain,
                entitySource = entitySource,
                virtualFileUrlManager = virtualFileUrlManager,
                storage = storage,
                libs = libs,
                libraryEntities = libraryEntities,
                onUnresolvedDependency = onUnresolvedDependency
            ),
            entitySource = entitySource
        ) {
            this.type = ModuleTypeId("JAVA_MODULE")

            // Add Kotlin facet if kotlin-maven-plugin is present
            val kotlinPlugin = findKotlinMavenPlugin(mavenProject)
            if (kotlinPlugin != null) {
                kotlinSettings += createKotlinFacet(mavenProject, moduleName, entitySource)
            }

            // Create content roots with source directories
            this.contentRoots = createContentRoots(
                mavenProject = mavenProject,
                isMain = isMain,
                virtualFileUrlManager = virtualFileUrlManager,
                entitySource = entitySource
            )
        }
    }

    private fun buildModuleDependencies(
        mavenProject: MavenProject,
        isMain: Boolean,
        entitySource: WorkspaceEntitySource,
        virtualFileUrlManager: VirtualFileUrlManager,
        storage: MutableEntityStorage,
        libs: MutableSet<String>,
        libraryEntities: MutableMap<String, LibraryEntity>,
        onUnresolvedDependency: (String) -> Unit
    ): List<ModuleDependencyItem> {
        return buildList {
            // Add library dependencies
            for (dependency in mavenProject.dependencies) {
                if (isMain && dependency.scope == "test") continue

                val libraryDep = createLibraryDependency(
                    dependency = dependency,
                    entitySource = entitySource,
                    virtualFileUrlManager = virtualFileUrlManager,
                    storage = storage,
                    libs = libs,
                    libraryEntities = libraryEntities,
                    onUnresolvedDependency = onUnresolvedDependency
                )

                libraryDep?.let { add(it) }
            }

            // Add module source dependency
            add(ModuleSourceDependency)

            // Add SDK dependency
            val jdkName = detectJdkName(mavenProject)
            if (jdkName != null) {
                add(SdkDependency(SdkId(jdkName, "JavaSDK")))
            } else {
                add(InheritedSdkDependency)
            }

            // Test module depends on main module
            if (!isMain) {
                add(
                    ModuleDependency(
                        module = ModuleId(getModuleName(mavenProject, isMain = true)),
                        exported = false,
                        scope = DependencyScope.COMPILE,
                        productionOnTest = false
                    )
                )
            }
        }
    }

    private fun createLibraryDependency(
        dependency: MavenArtifact,
        entitySource: WorkspaceEntitySource,
        virtualFileUrlManager: VirtualFileUrlManager,
        storage: MutableEntityStorage,
        libs: MutableSet<String>,
        libraryEntities: MutableMap<String, LibraryEntity>,
        onUnresolvedDependency: (String) -> Unit
    ): LibraryDependency? {
        val file = dependency.file
        if (file == null || !file.exists()) {
            onUnresolvedDependency("${dependency.groupId}:${dependency.artifactId}:${dependency.version}")
            return null
        }

        val libraryName = "Maven: ${dependency.groupId}:${dependency.artifactId}:${dependency.version}"

        // Only create library entity once (shared across modules)
        if (libs.add(libraryName)) {
            val libEntity = LibraryEntity(
                name = libraryName,
                tableId = ProjectLibraryTableId,
                roots = buildList {
                    // Add compiled JAR
                    add(
                        LibraryRoot(
                            file.toPath().toIntellijUri(virtualFileUrlManager),
                            LibraryRootTypeId.COMPILED
                        )
                    )

                    // Add sources JAR if available
                    val sourcesFile = findSourcesJar(dependency)
                    if (sourcesFile != null && sourcesFile.exists()) {
                        add(
                            LibraryRoot(
                                sourcesFile.toPath().toIntellijUri(virtualFileUrlManager),
                                LibraryRootTypeId.SOURCES
                            )
                        )
                    }
                },
                entitySource = entitySource
            ) {
                typeId = LibraryTypeId("java-imported")
            }

            storage addEntity libEntity
            libraryEntities[libraryName] = libEntity

            // Add Maven coordinates as library properties
            storage addEntity LibraryPropertiesEntity(entitySource) {
                propertiesXmlTag = "<properties groupId=\"${dependency.groupId}\" artifactId=\"${dependency.artifactId}\" version=\"${dependency.version}\" />"
                library = libEntity
            }
        }

        val scope = when (dependency.scope?.lowercase()) {
            "compile" -> DependencyScope.COMPILE
            "test" -> DependencyScope.TEST
            "provided" -> DependencyScope.PROVIDED
            "runtime" -> DependencyScope.RUNTIME
            else -> DependencyScope.COMPILE
        }

        return LibraryDependency(
            library = LibraryId(libraryName, ProjectLibraryTableId),
            exported = false,
            scope = scope
        )
    }

    private fun findSourcesJar(dependency: MavenArtifact): File? {
        val file = dependency.file ?: return null
        val parentDir = file.parentFile ?: return null
        val baseName = file.nameWithoutExtension

        // Try common patterns for sources JAR
        val patterns = listOf(
            "$baseName-sources.jar",
            "${dependency.artifactId}-${dependency.version}-sources.jar"
        )

        for (pattern in patterns) {
            val sourcesFile = File(parentDir, pattern)
            if (sourcesFile.exists()) {
                return sourcesFile
            }
        }

        return null
    }

    private fun createContentRoots(
        mavenProject: MavenProject,
        isMain: Boolean,
        virtualFileUrlManager: VirtualFileUrlManager,
        entitySource: WorkspaceEntitySource
    ): List<ContentRootEntity> {
        val projectDir = File(mavenProject.directory)

        return listOf(
            ContentRootEntity(
                url = projectDir.toPath().toIntellijUri(virtualFileUrlManager),
                excludedPatterns = emptyList(),
                entitySource = entitySource
            ) {
                this.sourceRoots = buildList {
                    if (isMain) {
                        // Main sources
                        addSourceRoot(File(projectDir, "src/main/java"), "java-source", virtualFileUrlManager, entitySource)
                        addSourceRoot(File(projectDir, "src/main/kotlin"), "java-source", virtualFileUrlManager, entitySource)
                        addSourceRoot(File(projectDir, "src/main/resources"), "java-resource", virtualFileUrlManager, entitySource)

                        // Add generated sources if they exist
                        mavenProject.generatedSourcesDirectory?.let { genSources ->
                            addSourceRoot(File(genSources), "java-source", virtualFileUrlManager, entitySource)
                        }
                    } else {
                        // Test sources
                        addSourceRoot(File(projectDir, "src/test/java"), "java-test", virtualFileUrlManager, entitySource)
                        addSourceRoot(File(projectDir, "src/test/kotlin"), "java-test", virtualFileUrlManager, entitySource)
                        addSourceRoot(File(projectDir, "src/test/resources"), "java-test-resource", virtualFileUrlManager, entitySource)

                        // Add generated test sources if they exist
                        mavenProject.generatedTestSourcesDirectory?.let { genTestSources ->
                            addSourceRoot(File(genTestSources), "java-test", virtualFileUrlManager, entitySource)
                        }
                    }
                }

                // Exclude target directory
                this.excludedUrls = listOfNotNull(
                    mavenProject.buildDirectory?.let { buildDir ->
                        val path = File(buildDir).toPath()
                        if (path.exists()) {
                            ExcludeUrlEntity(
                                url = path.toIntellijUri(virtualFileUrlManager),
                                entitySource = entitySource
                            )
                        } else null
                    }
                )
            }
        )
    }

    private fun MutableList<SourceRootEntity>.addSourceRoot(
        sourceDir: File,
        rootType: String,
        virtualFileUrlManager: VirtualFileUrlManager,
        entitySource: WorkspaceEntitySource
    ) {
        val path = sourceDir.toPath()
        if (path.exists()) {
            add(
                SourceRootEntity(
                    url = path.toIntellijUri(virtualFileUrlManager),
                    rootTypeId = SourceRootTypeId(rootType),
                    entitySource = entitySource
                )
            )
        }
    }

    private fun createKotlinFacet(
        mavenProject: MavenProject,
        moduleName: String,
        entitySource: WorkspaceEntitySource
    ): KotlinSettingsEntity {
        val platform = detectPlatform(mavenProject)
        val compilerArguments = extractCompilerArguments(mavenProject, platform)

        return KotlinSettingsEntity(
            name = KotlinFacetType.INSTANCE.presentableName,
            moduleId = ModuleId(moduleName),
            sourceRoots = emptyList(),
            configFileItems = emptyList(),
            useProjectSettings = false,
            implementedModuleNames = emptyList(),
            dependsOnModuleNames = emptyList(),
            additionalVisibleModuleNames = emptySet(),
            sourceSetNames = emptyList(),
            isTestModule = moduleName.endsWith(".test"),
            externalProjectId = "Maven: ${mavenProject.mavenId}",
            isHmppEnabled = true,
            pureKotlinSourceFolders = emptyList(),
            kind = KotlinModuleKind.DEFAULT,
            externalSystemRunTasks = emptyList(),
            version = KotlinFacetSettings.CURRENT_VERSION,
            flushNeeded = false,
            entitySource = entitySource
        )
    }

    private fun detectPlatform(mavenProject: MavenProject): IdePlatformKind {
        val plugin = findKotlinMavenPlugin(mavenProject) ?: return JvmIdePlatformKind

        // Check plugin executions for goals
        val goals = plugin.executions.flatMap { it.goals }

        return when {
            goals.contains("js") || goals.contains("test-js") -> JsIdePlatformKind
            goals.contains("metadata") -> CommonIdePlatformKind
            else -> JvmIdePlatformKind
        }
    }

    private fun extractCompilerArguments(
        mavenProject: MavenProject,
        platform: IdePlatformKind
    ): CommonCompilerArguments {
        val plugin = findKotlinMavenPlugin(mavenProject)
        val config = plugin?.configurationElement

        val arguments = when (platform) {
            JvmIdePlatformKind -> K2JVMCompilerArguments()
            JsIdePlatformKind -> K2JSCompilerArguments()
            else -> K2JVMCompilerArguments()
        }

        // Extract common arguments from configuration
        config?.let { element ->
            arguments.apiVersion = element.getChildTextTrim("apiVersion")
                ?: mavenProject.properties["kotlin.compiler.apiVersion"]?.toString()
            arguments.languageVersion = element.getChildTextTrim("languageVersion")
                ?: mavenProject.properties["kotlin.compiler.languageVersion"]?.toString()

            if (arguments is K2JVMCompilerArguments) {
                arguments.jvmTarget = element.getChildTextTrim("jvmTarget")
                    ?: mavenProject.properties["kotlin.compiler.jvmTarget"]?.toString()
                    ?: detectJvmTarget(mavenProject)
            }
        }

        return arguments
    }

    private fun detectJvmTarget(mavenProject: MavenProject): String {
        // Try to detect from maven-compiler-plugin
        val compilerPlugin = mavenProject.findPlugin("org.apache.maven.plugins", "maven-compiler-plugin")
        val target = compilerPlugin?.configurationElement?.getChildTextTrim("target")
            ?: mavenProject.properties["maven.compiler.target"]?.toString()
            ?: mavenProject.properties["java.version"]?.toString()

        return when (target) {
            "1.8", "8" -> JvmTarget.JVM_1_8.description
            "11" -> JvmTarget.JVM_11.description
            "17" -> JvmTarget.JVM_17.description
            "21" -> JvmTarget.JVM_21.description
            else -> JvmTarget.JVM_1_8.description
        }
    }

    private fun detectJdkName(mavenProject: MavenProject): String? {
        val javaVersion = mavenProject.properties["maven.compiler.source"]?.toString()
            ?: mavenProject.properties["maven.compiler.target"]?.toString()
            ?: mavenProject.properties["java.version"]?.toString()
            ?: "1.8"

        // Try to find a matching JDK
        val jdks = ProjectJdkTable.getInstance().allJdks
        val javaSdk = JavaSdk.getInstance()

        // Find JDK that matches the version
        val matchingJdk = jdks.firstOrNull { jdk ->
            jdk.sdkType == javaSdk && jdk.versionString?.contains(javaVersion) == true
        }

        return matchingJdk?.name
    }

    private fun findKotlinMavenPlugin(mavenProject: MavenProject): org.jetbrains.idea.maven.model.MavenPlugin? {
        return mavenProject.findPlugin(KOTLIN_PLUGIN_GROUP_ID, KOTLIN_PLUGIN_ARTIFACT_ID)
    }

    private fun getModuleName(mavenProject: MavenProject, isMain: Boolean): String {
        val baseName = mavenProject.mavenId.artifactId ?: "unnamed"
        return if (isMain) "$baseName.main" else "$baseName.test"
    }

    private fun isApplicableDirectory(projectDirectory: Path): Boolean {
        return (projectDirectory / "pom.xml").exists()
    }

    private fun readMavenProjects(projectDirectory: Path): List<MavenProject> {
        val pomFile = (projectDirectory / "pom.xml").toFile()

        // Use MavenProjectReader to read the POM
        val generalSettings = MavenGeneralSettings()
        generalSettings.isWorkOffline = false

        val embeddersManager = MavenServerManager.getInstance().createEmbeddersManager()

        try {
            val projectReader = MavenProjectReader(null)
            val readResult = projectReader.readProject(
                generalSettings,
                pomFile,
                MavenExplicitProfiles.NONE,
                null
            )

            val projects = mutableListOf<MavenProject>()
            projects.add(readResult.mavenProject)

            // Add all modules
            collectAllModules(readResult.mavenProject, projects, projectDirectory)

            return projects
        } finally {
            embeddersManager.releaseInTests()
        }
    }

    private fun collectAllModules(
        parentProject: MavenProject,
        projects: MutableList<MavenProject>,
        rootDirectory: Path
    ) {
        for (modulePath in parentProject.modulePaths) {
            val moduleDir = File(parentProject.directory, modulePath)
            val modulePomFile = if (moduleDir.isDirectory) {
                File(moduleDir, "pom.xml")
            } else {
                moduleDir
            }

            if (modulePomFile.exists()) {
                try {
                    val generalSettings = MavenGeneralSettings()
                    val projectReader = MavenProjectReader(null)
                    val moduleResult = projectReader.readProject(
                        generalSettings,
                        modulePomFile,
                        MavenExplicitProfiles.NONE,
                        null
                    )

                    projects.add(moduleResult.mavenProject)
                    collectAllModules(moduleResult.mavenProject, projects, rootDirectory)
                } catch (e: Exception) {
                    LOG.warn("Failed to read Maven module at $modulePomFile", e)
                }
            }
        }
    }
}
