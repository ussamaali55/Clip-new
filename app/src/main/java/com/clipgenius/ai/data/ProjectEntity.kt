package com.clipgenius.ai.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.clipgenius.ai.state.ProjectStage
import com.clipgenius.ai.state.ProjectStatus

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val videoUri: String = "",
    val currentStage: String = ProjectStage.IMPORTED.name,
    val status: String = ProjectStatus.ACTIVE.name,
    val stageDataJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val sourceVideoPath: String = videoUri,
    val sourceMediaJson: String? = null,
    val audioManifestJson: String? = null,
    val transcriptJson: String? = null,
    val clipCandidatesJson: String? = null,
    val verifiedClipsJson: String? = null,
    val stagesJson: String = "{}",
    val errorLog: String? = null,
    val activityLogsJson: String? = null
)
