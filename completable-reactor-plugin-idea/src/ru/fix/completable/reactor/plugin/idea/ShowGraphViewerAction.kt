package ru.fix.completable.reactor.plugin.idea

import com.intellij.notification.Notification
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.ProjectAndLibrariesScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import ru.fix.completable.reactor.api.Reactored
import ru.fix.completable.reactor.runtime.ReactorGraph

/**
 * @author Kamil Asfandiyarov
 */
class ShowGraphViewerAction : AnAction() {
    internal var log = Logger.getInstance(ShowGraphViewerAction::class.java)

    override fun actionPerformed(event: AnActionEvent) {
        val psiElement = event.getData(PlatformDataKeys.PSI_ELEMENT) ?: return



        val project = event.getData(PlatformDataKeys.PROJECT) ?: return
        val searchScope = ProjectAndLibrariesScope(project)

        val facade = JavaPsiFacade.getInstance(project)
        val annotationClass = facade.findClass(Reactored::class.java.name, searchScope) ?: return

        val reactorGraphClass = facade.findClass(ReactorGraph::class.java.name, searchScope) ?: return

        val buildingMethods = AnnotatedElementsSearch.searchPsiMethods(annotationClass, searchScope)
                .find { it.returnType == reactorGraphClass}

        Notification("reactor")
    }
}