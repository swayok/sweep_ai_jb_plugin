package dev.sweep.assistant.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction
import dev.sweep.assistant.autocomplete.edit.RecentEditsTracker
import dev.sweep.assistant.autocomplete.edit.RejectEditCompletionAction
import dev.sweep.assistant.settings.SweepSettings

class SweepActionPromoter : ActionPromoter {
    override fun promote(
        actions: List<AnAction>,
        context: DataContext,
    ): List<AnAction> {
        val project = context.getData(CommonDataKeys.PROJECT) ?: return actions
        val editor = context.getData(CommonDataKeys.EDITOR)

        // Autocomplete actions (when completion is shown) must be checked FIRST
        // because autocomplete is time-sensitive and ephemeral.
        if (editor != null && SweepSettings.getInstance().nextEditPredictionFlagOn) {
            val tracker = RecentEditsTracker.getInstance(project)
            if (tracker.isCompletionShown) {
                // Promote autocomplete accept/reject actions with highest priority
                val autocompleteActions =
                    actions.filter { action ->
                        action is AcceptEditCompletionAction || action is RejectEditCompletionAction
                    }
                if (autocompleteActions.isNotEmpty()) {
                    return autocompleteActions
                }
            }
        }

        // No promotion needed
        return actions
    }
}
