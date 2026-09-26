package com.clipgenius.ai.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipgenius.ai.data.ProjectRepository
import com.clipgenius.ai.pipeline.PipelineOrchestrator
import com.clipgenius.ai.state.Project
import com.clipgenius.ai.state.ProjectStage
import com.clipgenius.ai.state.ProjectStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val repository: ProjectRepository) : ViewModel() {

    val projects: StateFlow<List<Project>> = repository.allProjects
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun createNewProject(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val trimmedName = name.ifBlank { "Untitled Clip Project" }
            val newProject = Project(
                name = trimmedName,
                currentStage = ProjectStage.IMPORTED,
                status = ProjectStatus.ACTIVE
            )
            repository.saveProject(newProject)
            repository.activateProject(newProject.id)
            onCreated(newProject.id)
        }
    }

    fun openProject(context: Context, id: String, onOpened: () -> Unit) {
        viewModelScope.launch {
            // Activate this project and freeze any other active project
            repository.activateProject(id)
            onOpened()
        }
    }

    fun duplicateProject(context: Context, id: String) {
        viewModelScope.launch {
            repository.duplicateProject(context, id)
        }
    }

    fun deleteProject(context: Context, id: String) {
        viewModelScope.launch {
            // If deleting the active project, pause orchestrator
            if (PipelineOrchestrator.activeProjectId.value == id) {
                PipelineOrchestrator.freezeActiveProject(context, repository, id)
            }
            repository.deleteProject(context, id)
        }
    }
}

class HomeViewModelFactory(private val repository: ProjectRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(repository) as T
    }
}
