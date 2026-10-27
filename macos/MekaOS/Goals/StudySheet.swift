@preconcurrency import MekaKit
import SwiftUI

/// Goals' STUDY section on the Mac (study plan, slice 1), like the Fold's StudySection: one row per course (its ring
/// with the percentage, the name, "5 of 24 lessons · about 18 h left" and the next lesson), a click opening the
/// course's sheet ([StudySheet]); then "Add a course…" (the new course opens). A course removed in its sheet comes
/// back with Undo here for a few seconds.
struct StudySection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    @State private var said: CoreModel.StudySaid?
    @State private var openId: String?
    @State private var removed: CoreModel.TripUndo?

    var body: some View {
        let courses = model.study?.courses ?? []
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel(StudyRules.shared.SECTION, palette)
            ForEach(courses, id: \.id) { c in
                Button {
                    MekaHaptics.light()
                    openId = c.id
                } label: {
                    HStack(spacing: MekaSpace.m) {
                        StudyRing(fraction: Double(c.fraction), label: "\(c.pct)", size: 40, width: 4, sub: nil, palette: palette)
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text(c.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                            Text(c.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            if let next = c.next {
                                Text(next).font(MekaType.caption).foregroundStyle(c.allDone ? palette.success : palette.textTertiary)
                            }
                        }
                        Spacer(minLength: MekaSpace.xs)
                        Image(systemName: "chevron.right").foregroundStyle(palette.accent)
                    }
                    .padding(.vertical, MekaSpace.xs)
                    .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel("Open \(c.spoken)")
                .transition(.opacity)
            }
            if let removed {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(removed.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Spacer()
                    Button("Undo") {
                        let token = removed.token
                        self.removed = nil
                        model.undoStudyRemoval(token)
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                    .accessibilityLabel("Undo: \(removed.line)")
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                .task(id: removed) {
                    try? await Task.sleep(for: .milliseconds(TripRules.shared.UNDO_MS))
                    if !Task.isCancelled { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { self.removed = nil } }
                }
            }
            TextField(StudyRules.shared.ADD_COURSE_HINT, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = text
                    text = ""
                    Task {
                        let r = await model.addStudyCourse(typed)
                        said = r
                        if let id = r?.courseId { openId = id }
                    }
                }
            if courses.isEmpty || said?.refused == true {
                Text(said?.refused == true ? said?.line ?? "" : StudyRules.shared.ADD_COURSE_NOTE)
                    .font(MekaType.caption)
                    .foregroundStyle(said?.refused == true ? palette.accent : palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .animation(MekaMotion.replan(reduced: reduceMotion), value: courses.map(\.id))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: removed)
        .sheet(isPresented: Binding(get: { openId != nil }, set: { if !$0 { openId = nil } })) {
            if let id = openId {
                StudySheet(courseId: id, palette: palette, onClose: { openId = nil }, onRemoved: { removed = $0 })
            }
        }
    }
}

/// A progress ring that sweeps round from the top on appear and glides on a change; the number inside.
struct StudyRing: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let fraction: Double
    let label: String
    let size: CGFloat
    let width: CGFloat
    let sub: String?
    let palette: MekaPalette
    @State private var shown = false

    var body: some View {
        ZStack {
            Circle().stroke(palette.surfaceRaised, lineWidth: width)
            Circle().trim(from: 0, to: CGFloat(shown || reduceMotion ? min(max(fraction, 0), 1) : 0))
                .stroke(palette.accent, style: StrokeStyle(lineWidth: width, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: fraction)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: shown)
            VStack(spacing: 0) {
                Text(label).font(sub == nil ? MekaType.caption : MekaType.nowTitle)
                    .foregroundStyle(sub == nil ? palette.textSecondary : palette.textPrimary)
                    .monospacedDigit()
                    .contentTransition(.numericText())
                if let sub {
                    Text(sub).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
            }
        }
        .frame(width: size, height: size)
        .onAppear { shown = true }
    }
}

/// Goals → Study → a course on the Mac (study plan, slice 1), like the Fold's StudyPane: the big ring with the share
/// done, the name, "5 of 24 lessons · about 18 h left" and the next lesson; LESSONS with the outline field (paste the
/// course outline: one lesson a line, "Module 2: Core skills" starts a module, "45 min" its length; ⌘↩ adds) and each
/// module folding on a click with its count, its lessons in course order. A tick shows at once and moves the ring,
/// then is written; right-click → Remove, with Undo. Remove course at the foot (Undo on Goals). Synced with the Fold.
///
/// Motion: the header and lessons stagger in; the ring sweeps round on appear and glides on a tick; modules fold on
/// the expand spring with the chevron turning; a lesson's ring draws its check and the title fades; the line under the
/// field cross-fades. Reduce Motion: cross-fades.
struct StudySheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let courseId: String
    let palette: MekaPalette
    let onClose: () -> Void
    let onRemoved: (CoreModel.TripUndo) -> Void
    @State private var outline = ""
    @State private var said: CoreModel.StudySaid?
    @State private var removed: CoreModel.TripUndo?
    /// What this sheet ticked, until the view says the same.
    @State private var pending: [String: Bool] = [:]
    /// Modules opened or folded by hand; untouched, the first not yet done is open.
    @State private var opened: [String: Bool] = [:]
    /// Slice 2a: the plan's chips clicked here, worked out at once until the view says the same.
    @State private var tapFinish: Int64?
    @State private var tapSession: Int32?
    @State private var tapSlots: [String: Bool] = [:]
    /// Study fixes: the lesson or module being renamed (and the typed name), the module "+ Add lesson" is open on, the new
    /// module's fields, what a Delete is asking about, and Recently deleted unfolded.
    @State private var renameId: String?
    @State private var renameModule: String?
    @State private var renameText = ""
    @State private var addInto: String?
    @State private var addText = ""
    @State private var addingModule = false
    @State private var moduleName = ""
    @State private var moduleLessons = ""
    @State private var asking: StudyAsk?
    @State private var showDeleted = false

    /// What a Delete confirm is about: a lesson (its id), a module (its name) or the course; [question] is the core's line.
    struct StudyAsk: Identifiable, Equatable {
        enum Kind { case lesson, module, course }
        let kind: Kind
        let key: String
        let question: String
        var id: String { "\(kind)-\(key)" }
    }

    var body: some View {
        let c = model.study?.course(id: courseId)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Button("‹ Goals and habits", action: onClose)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
                .keyboardShortcut(.cancelAction)
                .accessibilityLabel("Back to Goals and habits")
            if let c {
                content(c)
            } else {
                Text(StudyRules.shared.GONE).font(MekaType.body).foregroundStyle(palette.textSecondary)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 520, alignment: .topLeading)
        .background(palette.surface)
        .onChange(of: model.study) { _, v in
            guard let c = v?.course(id: courseId) else { pending = [:]; return }
            var shown: [String: Bool] = [:]
            for m in c.modules { for l in m.lessons { shown[l.id] = l.done } }
            pending = pending.filter { id, done in shown[id] != nil && shown[id] != done }
            let i = c.planInput
            if let f = tapFinish, i.finishByDay?.int64Value == f { tapFinish = nil }
            if let m = tapSession, i.sessionMins == m { tapSession = nil }
            tapSlots = tapSlots.filter { key, on in i.slots.contains(key) != on }
        }
    }

    /// The plan card for [c], with any choice clicked here applied (the same rules the core runs).
    private func plan(_ c: StudyCourseView) -> StudyPlanView {
        if tapFinish == nil && tapSession == nil && tapSlots.isEmpty { return c.plan }
        var i = c.planInput
        if let f = tapFinish, i.finishByDay?.int64Value != f { i = i.withFinish(day: f) }
        if let m = tapSession { i = i.withSession(mins: m) }
        for (key, on) in tapSlots { i = i.withSlot(key: key, on: on) }
        return StudyRules.shared.plan(i: i)
    }

    private func isDone(_ l: StudyLessonRow) -> Bool { pending[l.id] ?? l.done }

    @ViewBuilder
    private func content(_ c: StudyCourseView) -> some View {
        let lessons = c.modules.flatMap { $0.lessons }
        let done = lessons.filter { isDone($0) }.count
        let total = lessons.count
        let pct = total == 0 ? 0 : (done * 100 + total / 2) / total
        let p = plan(c)
        HStack(spacing: MekaSpace.l) {
            StudyRing(fraction: total == 0 ? 0 : Double(done) / Double(total), label: "\(pct)%", size: 96, width: 8, sub: "done", palette: palette)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(c.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
                Text(c.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                if let next = c.next {
                    Text(next).font(MekaType.captionStrong).foregroundStyle(c.allDone ? palette.success : palette.accent)
                }
                if let status = p.status {
                    Text(status).font(MekaType.caption).foregroundStyle(p.behind ? palette.accent : palette.success)
                        .contentTransition(.opacity)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(c.title), \(pct) percent done, \(c.line)" + (p.status.map { ", \($0)" } ?? ""))
        .staggeredAppear(0)
        if total > 0 && !c.allDone {
            planCard(c, p).padding(.top, MekaSpace.m).staggeredAppear(1)
        }
        SectionLabel("Lessons", palette).padding(.top, MekaSpace.l).staggeredAppear(1)
        TextField(StudyRules.shared.ADD_LESSONS_HINT, text: $outline, axis: .vertical)
            .lineLimit(1...8)
            .textFieldStyle(.roundedBorder)
            .accessibilityLabel(StudyRules.shared.ADD_LESSONS_DESCRIPTION)
            .onSubmit(add)
            .staggeredAppear(1)
        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
            Text(said?.line ?? StudyRules.shared.ADD_LESSONS_NOTE).font(MekaType.caption)
                .foregroundStyle(said?.refused == true ? palette.accent : (said == nil ? palette.textTertiary : palette.textSecondary))
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
            Spacer()
            if !outline.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Button("Add lessons", action: add)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.captionStrong)
                    .foregroundStyle(palette.accent)
                    .keyboardShortcut(.return, modifiers: .command)
                    .transition(.opacity)
            }
        }
        if let removed {
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                Text(removed.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Spacer()
                Button("Undo") {
                    let token = removed.token
                    self.removed = nil
                    model.undoStudyRemoval(token)
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
                .accessibilityLabel("Undo: \(removed.line)")
            }
            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            .task(id: removed) {
                try? await Task.sleep(for: .milliseconds(TripRules.shared.UNDO_MS))
                if !Task.isCancelled { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { self.removed = nil } }
            }
        }
        let firstOpen = c.modules.first { m in m.lessons.contains { !isDone($0) } }?.key
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                if c.modules.isEmpty {
                    Text(StudyRules.shared.EMPTY_COURSE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, MekaSpace.xs)
                }
                let names = c.modules.map { $0.name }
                ForEach(c.modules, id: \.key) { m in
                    module(m, open: opened[m.key] ?? (m.key == firstOpen), names: names)
                }
                addModuleRow
                if !c.deleted.isEmpty { deletedSection(c.deleted) }
                Text(StudyRules.shared.NOTE + " Right-click a lesson or a module to rename, move or delete it.")
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, MekaSpace.m)
                Button("Remove course") {
                    asking = StudyAsk(kind: .course, key: courseId, question: StudyRules.shared.confirmRemoveCourse(title: c.title, n: Int32(c.total)))
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.critical)
                .accessibilityLabel("Remove \(c.title) and its lessons")
            }
            .animation(MekaMotion.expand(reduced: reduceMotion), value: pending)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: opened)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: renameId)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: addInto)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: showDeleted)
        }
        .frame(maxHeight: 420)
        .confirmationDialog(asking?.question ?? "", isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } }), presenting: asking) { a in
            Button(a.kind == .course ? "Remove" : StudyRules.shared.DELETE, role: .destructive) { confirm(a) }
            Button(StudyRules.shared.CANCEL, role: .cancel) { asking = nil }
        }
    }

    /// The confirm said yes: only now is anything deleted (with Undo, and Recently deleted for lessons).
    private func confirm(_ a: StudyAsk) {
        asking = nil
        let key = a.key
        let course = courseId
        Task {
            switch a.kind {
            case .lesson: if let u = await model.removeStudyLesson(key) { removed = u }
            case .module: if let u = await model.removeStudyModule(course, module: key) { removed = u }
            case .course:
                if let u = await model.removeStudyCourse(course) {
                    onRemoved(u)
                    onClose()
                }
            }
        }
    }

    /// "+ Add module": its name, then its first lessons (one a line).
    @ViewBuilder
    private var addModuleRow: some View {
        Button(StudyRules.shared.ADD_MODULE) { addingModule.toggle() }
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(palette.accent)
            .padding(.top, MekaSpace.xs)
            .accessibilityLabel("Add a module")
        if addingModule {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                TextField(StudyRules.shared.ADD_MODULE_HINT, text: $moduleName).textFieldStyle(.roundedBorder)
                TextField(StudyRules.shared.ADD_MODULE_LESSONS_HINT, text: $moduleLessons, axis: .vertical)
                    .lineLimit(1...6)
                    .textFieldStyle(.roundedBorder)
                Button("Add module") {
                    let name = moduleName
                    let text = moduleLessons
                    let course = courseId
                    Task {
                        let r = await model.addStudyModule(course, name: name, text)
                        said = r
                        if let r, !r.refused { moduleName = ""; moduleLessons = ""; addingModule = false }
                    }
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.captionStrong)
                .foregroundStyle(palette.accent)
            }
            .transition(.opacity)
        }
    }

    /// Recently deleted, folded: each lesson with "From <module>" and Restore.
    @ViewBuilder
    private func deletedSection(_ rows: [StudyDeletedRow]) -> some View {
        Button {
            MekaHaptics.tick()
            showDeleted.toggle()
        } label: {
            HStack {
                Text("\(StudyRules.shared.RECENT_LABEL) · \(rows.count)").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Spacer()
                Image(systemName: "chevron.down").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .rotationEffect(.degrees(showDeleted ? 180 : 0))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .padding(.top, MekaSpace.m)
        .accessibilityLabel("\(StudyRules.shared.RECENT_LABEL), \(rows.count)")
        if showDeleted {
            ForEach(rows, id: \.id) { d in
                HStack(spacing: MekaSpace.xs) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(d.title).font(MekaType.body).foregroundStyle(palette.textSecondary)
                        Text(d.line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    Spacer()
                    let id = d.id
                    Button(StudyRules.shared.RESTORE) { model.restoreStudyLesson(id) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.captionStrong)
                        .foregroundStyle(palette.accent)
                        .accessibilityLabel("Restore \(d.title)")
                }
                .padding(.leading, MekaSpace.m)
                .transition(.opacity)
            }
        }
    }

    /// YOUR PLAN (slice 2a), like the Fold's: FINISH BY, EACH SESSION and WHEN YOU CAN STUDY as chips, then sessions a
    /// week, whether it fits and Obi's line. A chip lights at once (its fill glides); the lines cross-fade.
    @ViewBuilder
    private func planCard(_ c: StudyCourseView, _ p: StudyPlanView) -> some View {
        let id = c.id
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel(StudyRules.shared.PLAN, palette)
            chipRow(StudyRules.shared.FINISH_LABEL, p.ends) { chip in
                guard !chip.on, let day = Int64(chip.key) else { return }
                let before = tapFinish
                tapFinish = day
                Task { if !(await model.setStudyFinish(id, day: day)) { tapFinish = before } }
            }
            chipRow(StudyRules.shared.SESSION_LABEL, p.lens) { chip in
                guard !chip.on, let mins = Int32(chip.key) else { return }
                let before = tapSession
                tapSession = mins
                Task { if !(await model.setStudySession(id, mins: mins)) { tapSession = before } }
            }
            chipRow(StudyRules.shared.SLOTS_LABEL, p.slots) { chip in
                let key = chip.key
                let on = !chip.on
                tapSlots[key] = on
                Task { if !(await model.setStudySlot(id, key: key, on: on)) { tapSlots[key] = nil } }
            }
            if let perWeek = p.perWeek {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(perWeek).font(MekaType.nowTitle).foregroundStyle(palette.accent).contentTransition(.numericText())
                    if let sub = p.perWeekSub { Text(sub).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
                }
            }
            if let fit = p.fit {
                Text(fit).font(MekaType.caption)
                    .foregroundStyle(p.fits ? palette.success : (p.perWeek == nil ? palette.textTertiary : palette.accent))
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
            }
            if let obi = p.obi {
                HStack(alignment: .top, spacing: MekaSpace.xs) {
                    Circle().fill(RadialGradient(colors: [palette.accent.opacity(0.9), palette.accent.opacity(0.45)], center: .center, startRadius: 0, endRadius: 8))
                        .frame(width: 16, height: 16)
                    Text(obi).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, MekaSpace.m)
                .padding(.vertical, MekaSpace.xs)
                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(MekaSpace.m)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.l))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: p.spoken)
    }

    private func chipRow(_ label: String, _ chips: [StudyChip], _ tap: @escaping (StudyChip) -> Void) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            HStack(spacing: MekaSpace.xs) {
                ForEach(chips, id: \.key) { chip in
                    Button {
                        MekaHaptics.light()
                        tap(chip)
                    } label: {
                        Text(chip.label).font(MekaType.caption).lineLimit(1)
                            .foregroundStyle(chip.on ? palette.onAccent : palette.textPrimary)
                            .padding(.horizontal, MekaSpace.xs)
                            .padding(.vertical, MekaSpace.xxs)
                            .background(chip.on ? palette.accent : palette.surfaceRaised, in: Capsule())
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: chip.on)
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel(chip.spoken)
                    .accessibilityAddTraits(chip.on ? .isSelected : [])
                }
            }
        }
    }

    private func add() {
        let typed = outline
        guard !typed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        Task {
            let r = await model.addStudyLessons(courseId, typed)
            said = r
            if let r, !r.refused { outline = "" }
        }
    }

    @ViewBuilder
    private func module(_ m: StudyModule, open: Bool, names: [String]) -> some View {
        let done = m.lessons.filter { isDone($0) }.count
        let count = StudyRules.shared.moduleCount(done: Int32(done), total: Int32(m.lessons.count))
        let allDone = done == m.lessons.count
        Button {
            MekaHaptics.tick()
            opened[m.key] = !open
        } label: {
            HStack(spacing: MekaSpace.xs) {
                Text(m.name).font(MekaType.taskTitle).foregroundStyle(palette.textPrimary)
                Spacer()
                Text(count).font(MekaType.caption).foregroundStyle(allDone ? palette.success : palette.textTertiary)
                Image(systemName: "chevron.down").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .rotationEffect(.degrees(open ? 180 : 0))
            }
            .padding(.horizontal, MekaSpace.m)
            .padding(.vertical, MekaSpace.xs)
            .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .padding(.top, MekaSpace.xs)
        .accessibilityLabel("\(m.name), \(count)")
        .contextMenu {
            let name = m.name
            let n = m.lessons.count
            Button("\(StudyRules.shared.RENAME)…") { renameText = name; renameId = nil; renameModule = name }
            Divider()
            Button("\(StudyRules.shared.DELETE_MODULE)…") {
                asking = StudyAsk(kind: .module, key: name, question: StudyRules.shared.confirmDeleteModule(name: name, n: Int32(n)))
            }
        }
        if renameModule == m.name {
            renameField("Rename \(m.name)") { text in
                let name = m.name
                model.renameStudyModule(courseId, module: name, text)
            }
        }
        if open {
            ForEach(m.lessons, id: \.id) { l in
                lesson(l, module: m.name, names: names)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                if renameId == l.id {
                    renameField("Rename \(l.title)") { text in
                        let id = l.id
                        model.renameStudyLesson(id, text)
                    }
                }
            }
            if addInto == m.name {
                TextField(StudyRules.shared.ADD_LESSON_HINT, text: $addText, axis: .vertical)
                    .lineLimit(1...6)
                    .textFieldStyle(.roundedBorder)
                    .padding(.leading, MekaSpace.m)
                    .accessibilityLabel("Add lessons to \(m.name)")
                    .onSubmit {
                        let text = addText
                        let name = m.name
                        let course = courseId
                        Task {
                            let r = await model.addStudyLessonsTo(course, module: name, text)
                            said = r
                            if let r, !r.refused { addText = ""; addInto = nil }
                        }
                    }
            } else {
                Button(StudyRules.shared.ADD_LESSON) { addText = ""; addInto = m.name }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                    .padding(.leading, MekaSpace.m)
                    .accessibilityLabel("Add a lesson to \(m.name)")
            }
        }
    }

    /// A field starting from the name; Return renames, Escape leaves it.
    private func renameField(_ label: String, _ done: @escaping (String) -> Void) -> some View {
        TextField(label, text: $renameText)
            .textFieldStyle(.roundedBorder)
            .padding(.leading, MekaSpace.m)
            .accessibilityLabel(label)
            .onSubmit {
                let text = renameText
                renameId = nil
                renameModule = nil
                if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { done(text) }
            }
            .onExitCommand { renameId = nil; renameModule = nil }
            .transition(.opacity)
    }

    private func lesson(_ l: StudyLessonRow, module: String, names: [String]) -> some View {
        let done = isDone(l)
        let id = l.id
        return Button {
            pending[id] = !done
            MekaHaptics.light()
            Task {
                if !(await model.tickStudyLesson(id, done: !done)) { pending[id] = nil }
            }
        } label: {
            HStack(spacing: MekaSpace.xs) {
                TickRingView(done: done, palette: palette).frame(width: 18, height: 18)
                Text(l.title).font(MekaType.taskTitle).foregroundStyle(palette.textPrimary)
                Spacer()
                if !l.mins.isEmpty {
                    Text(l.mins).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
            }
            .opacity(done ? 0.5 : 1)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: done)
            .padding(.vertical, MekaSpace.xxs)
            .padding(.leading, MekaSpace.m)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(l.title)
        .accessibilityValue(done ? "Done" : "Not done")
        .contextMenu {
            let title = l.title
            Button("\(StudyRules.shared.RENAME)…") { renameText = title; renameModule = nil; renameId = id }
            if names.count > 1 {
                Menu(StudyRules.shared.MOVE_LABEL) {
                    ForEach(names.filter { $0 != module }, id: \.self) { name in
                        Button(name) { model.moveStudyLesson(id, module: name) }
                    }
                }
            }
            Button(done ? StudyRules.shared.MARK_UNDONE : StudyRules.shared.MARK_DONE) {
                pending[id] = !done
                Task { if !(await model.tickStudyLesson(id, done: !done)) { pending[id] = nil } }
            }
            Divider()
            Button("\(StudyRules.shared.DELETE)…") {
                asking = StudyAsk(kind: .lesson, key: id, question: StudyRules.shared.confirmDelete(title: title))
            }
        }
    }
}
