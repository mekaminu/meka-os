import SwiftUI

// Rules behind the shell's shared transitions (build plan M1, App shell). The parts both apps use match
// android/.../shell/SharedMotion.kt. The Mac never folds, so the Fold's unfold morph has no counterpart here.

enum SharedMotion {
    /// How long tasks that Plan Apply just sent into Today stay softly lit.
    static let landedSeconds: Double = 1.2

    /// One key per task: the list row, the detail and the plan block all share it.
    static func taskKey(_ id: String) -> String { "task-\(id)" }

    /// The id the selection highlight carries as it glides from row to row.
    static let selectionKey = "selection"

    /// A task that just landed from the plan is lit once the plan has gone.
    static func highlightLanded(_ id: String, planOpen: Bool, landing: Set<String>) -> Bool {
        !planOpen && landing.contains(id)
    }
}

extension EnvironmentValues {
    /// The namespace a list's selection highlight glides in (matchedGeometryEffect); nil means no glide.
    @Entry var selectionNamespace: Namespace.ID? = nil
}

extension View {
    /// The selection highlight: glides between rows within [namespace]; with Reduce Motion it simply cross-fades.
    @ViewBuilder
    func selectionGlide(_ namespace: Namespace.ID?, reduced: Bool) -> some View {
        if let namespace, !reduced {
            matchedGeometryEffect(id: SharedMotion.selectionKey, in: namespace)
        } else {
            self
        }
    }
}
