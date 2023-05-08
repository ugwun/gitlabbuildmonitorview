package com.csadovsky

import org.gitlab4j.api.Constants
import org.gitlab4j.api.GitLabApi
import org.gitlab4j.api.models.*
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

fun main(args: Array<String>) {
    runApplication<GitlabBuildMonitorViewApplication>(*args)
}

@SpringBootApplication
@EnableConfigurationProperties(GitlabBuildMonitorViewProperties::class)
class GitlabBuildMonitorViewApplication {
    @Bean
    fun gitLabApi(gitlabBuildMonitorViewProperties: GitlabBuildMonitorViewProperties): GitLabApi {
        val gitLabApi = GitLabApi(gitlabBuildMonitorViewProperties.host, gitlabBuildMonitorViewProperties.personalAccessToken)
        gitLabApi.setRequestTimeout(10_000, 10_000);
        return gitLabApi
    }
}

@ConfigurationProperties(prefix = "gitlabbuildmonitorview")
class GitlabBuildMonitorViewProperties {
    lateinit var host: String
    lateinit var personalAccessToken: String
    lateinit var projects: List<String>
}

@Controller
class ProjectHtmlController(val gitLabApi: GitLabApi, val gitlabBuildMonitorViewProperties: GitlabBuildMonitorViewProperties) {
    private val firstListOfPipelines = mutableListOf<Pipeline>()
    private val secondListofPipelines = mutableListOf<Pipeline>()
    @RequestMapping("/")
    fun showData(model: Model): String {
        val pipelines = try {
            getPipelines()
        } catch (e: Exception) {
            System.err.println("Exception from showData method!")
            emptyList<Pipeline>()
        }

        if (firstListOfPipelines.isEmpty()) {
            firstListOfPipelines.addAll(pipelines)
            secondListofPipelines.clear()
        }
        model.addAttribute("pipelines", pipelines)

        return "project-template"
    }

    fun getPipelines(): List<Pipeline> {
        return gitlabBuildMonitorViewProperties.projects.mapNotNull { projectName ->
            kotlin.runCatching {
                val project = gitLabApi.projectApi.getProject(projectName)
                val branchName = project.defaultBranch
                val lastPipeline = gitLabApi.pipelineApi.getPipelines(
                        projectName,
                        PipelineFilter()
                                .withOrderBy(Constants.PipelineOrderBy.UPDATED_AT)
                                .withSort(Constants.SortOrder.ASC)
                                .withRef(branchName),
                        1
                ).last().firstOrNull() ?: return@mapNotNull null

                val lastPipelineStatus = PipelineStatus.SUCCESS == lastPipeline.status
                val running = PipelineStatus.RUNNING == lastPipeline.status
                Pipeline(projectName, lastPipelineStatus, running, project.namespace.name, lastPipeline.webUrl)
            }.onFailure {
                System.err.println("Exception from getPipelines method!")
            }.getOrNull()
        }
    }

    @GetMapping("/reloadState")
    fun compare(): ResponseEntity<String> {
        val pipelines = getPipelines()

        if (firstListOfPipelines.isNotEmpty()){
            secondListofPipelines.addAll(pipelines)
            if (firstListOfPipelines != secondListofPipelines) {
                secondListofPipelines.clear()
                firstListOfPipelines.clear()
                return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build()
            }
        }
        secondListofPipelines.clear()
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }
}

data class Pipeline(val name: String, val status: Boolean, val running: Boolean, val category: String, val url: String)


