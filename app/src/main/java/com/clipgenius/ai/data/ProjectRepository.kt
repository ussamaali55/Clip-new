package com.clipgenius.ai.data

import com.clipgenius.ai.state.Project
import com.clipgenius.ai.state.defaultStageStatuses
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProjectRepository(private val projectDao: ProjectDao) {

    private val typeConverters = ProjectTypeConverters()

    val allProjects: Flow<List<Project>> = projectDao.getAllProjects().map { entities ->
        entities.map { it.toDomainModel() }
    }

    // --- Caption Presets ---
    val allPresets: Flow<List<com.clipgenius.ai.state.CaptionPreset>> = projectDao.getAllPresets().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun savePreset(preset: com.clipgenius.ai.state.CaptionPreset) {
        projectDao.insertPreset(CaptionPresetEntity.fromDomain(preset))
    }

    suspend fun deletePreset(id: String) {
        projectDao.deletePresetById(id)
    }

    suspend fun getProjectById(id: String): Project? {
        return projectDao.getProjectById(id)?.toDomainModel()
    }

    suspend fun saveProject(project: Project) {
        val updated = project.copy(updatedAt = System.currentTimeMillis())
        projectDao.insertProject(updated.toEntity())
    }

    suspend fun activateProject(projectId: String) {
        val now = System.currentTimeMillis()
        projectDao.freezeOtherProjects(projectId, now)
        projectDao.updateProjectStatus(projectId, com.clipgenius.ai.state.ProjectStatus.ACTIVE.name, now)
    }

    suspend fun freezeProject(projectId: String) {
        projectDao.updateProjectStatus(projectId, com.clipgenius.ai.state.ProjectStatus.FROZEN.name, System.currentTimeMillis())
    }

    suspend fun updateProjectStage(projectId: String, stage: com.clipgenius.ai.state.ProjectStage) {
        projectDao.updateProjectStage(projectId, stage.name, System.currentTimeMillis())
    }

    suspend fun deleteProject(context: android.content.Context, id: String) {
        // 1. Remove database record
        projectDao.deleteProjectById(id)
        // 2. Remove intermediate files directory namespaced to this project
        // IMPORTANT: Original video at sourceVideoPath / videoUri is NOT deleted.
        try {
            val projectDir = java.io.File(context.filesDir, "projects/$id")
            if (projectDir.exists()) {
                projectDir.deleteRecursively()
            }
        } catch (e: Exception) {
            android.util.Log.e("ProjectRepository", "Failed to delete project cache files", e)
        }
    }

    suspend fun deleteProject(id: String) {
        projectDao.deleteProjectById(id)
    }

    suspend fun duplicateProject(context: android.content.Context, originalId: String): Project? {
        val original = getProjectById(originalId) ?: return null
        val newId = java.util.UUID.randomUUID().toString()
        val newName = "${original.name} (Copy)"
        
        // Copy project private files if existing
        val origDir = java.io.File(context.filesDir, "projects/$originalId")
        val newDir = java.io.File(context.filesDir, "projects/$newId")
        if (origDir.exists()) {
            origDir.copyRecursively(newDir, overwrite = true)
        }

        // Remap file paths in verified clips
        val remappedClips = original.verifiedClips.map { clip ->
            clip.copy(
                clipId = java.util.UUID.randomUUID().toString(),
                draftVideoPath = clip.draftVideoPath?.replace(originalId, newId),
                verticalDraftPath = clip.verticalDraftPath?.replace(originalId, newId),
                facetrackJsonPath = clip.facetrackJsonPath?.replace(originalId, newId),
                layoutJsonPath = clip.layoutJsonPath?.replace(originalId, newId),
                captionsJsonPath = clip.captionsJsonPath?.replace(originalId, newId),
                finalExportPath = clip.finalExportPath?.replace(originalId, newId),
                metadataTxtPath = clip.metadataTxtPath?.replace(originalId, newId)
            )
        }

        val duplicated = original.copy(
            id = newId,
            name = newName,
            createdAt = System.currentTimeMillis(),
            verifiedClips = remappedClips
        )
        saveProject(duplicated)
        return duplicated
    }

    private fun ProjectEntity.toDomainModel(): Project {
        val stagesMap = typeConverters.toStagesMap(stagesJson)
        val sourceMedia = typeConverters.toSourceMedia(sourceMediaJson)
        val audioManifest = typeConverters.toAudioChunkManifest(audioManifestJson)
        val transcript = typeConverters.toProjectTranscript(transcriptJson)
        val clipCandidates = typeConverters.toClipCandidates(clipCandidatesJson)
        val verifiedClips = typeConverters.toVerifiedClips(verifiedClipsJson)
        val activityLogs = typeConverters.toActivityLogs(activityLogsJson)
        val parsedStage = typeConverters.toProjectStage(currentStage)
        val parsedStatus = typeConverters.toProjectStatus(status)
        val resolvedVideoPath = if (sourceVideoPath.isNotBlank()) sourceVideoPath else videoUri
        return Project(
            id = id,
            name = name,
            createdAt = createdAt,
            updatedAt = if (updatedAt > 0) updatedAt else createdAt,
            sourceVideoPath = resolvedVideoPath,
            videoUri = if (videoUri.isNotBlank()) videoUri else resolvedVideoPath,
            currentStage = parsedStage,
            status = parsedStatus,
            stageDataJson = stageDataJson,
            sourceMedia = sourceMedia,
            audioManifest = audioManifest,
            transcript = transcript,
            clipCandidates = clipCandidates,
            verifiedClips = verifiedClips,
            stages = if (stagesMap.isEmpty()) defaultStageStatuses() else stagesMap,
            errorLog = errorLog,
            activityLogs = activityLogs
        )
    }

    private fun Project.toEntity(): ProjectEntity {
        val resolvedVideoUri = if (videoUri.isNotBlank()) videoUri else sourceVideoPath
        val resolvedStageData = stageDataJson ?: runCatching {
            org.json.JSONObject().apply {
                put("stage", currentStage.name)
                put("hasTranscript", transcript != null)
                put("clipCount", verifiedClips.size.takeIf { it > 0 } ?: clipCandidates.size)
            }.toString()
        }.getOrNull()

        return ProjectEntity(
            id = id,
            name = name,
            videoUri = resolvedVideoUri,
            currentStage = currentStage.name,
            status = status.name,
            stageDataJson = resolvedStageData,
            createdAt = createdAt,
            updatedAt = updatedAt,
            sourceVideoPath = if (sourceVideoPath.isNotBlank()) sourceVideoPath else resolvedVideoUri,
            sourceMediaJson = typeConverters.fromSourceMedia(sourceMedia),
            audioManifestJson = typeConverters.fromAudioChunkManifest(audioManifest),
            transcriptJson = typeConverters.fromProjectTranscript(transcript),
            clipCandidatesJson = typeConverters.fromClipCandidates(clipCandidates),
            verifiedClipsJson = typeConverters.fromVerifiedClips(verifiedClips),
            stagesJson = typeConverters.fromStagesMap(stages),
            errorLog = errorLog,
            activityLogsJson = typeConverters.fromActivityLogs(activityLogs)
        )
    }
}
