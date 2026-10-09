@preconcurrency import MekaKit
import SwiftUI

/// Weather places (Calendars → Weather): **Home**, the town the forecast is for, Biggleswade unless Meka types another,
/// and **Work** (Places item 2), Canary Wharf unless he types another; on office days Today's weather line and the brief
/// say both, and the Day ring's rain follows where he'll be. Return saves a field (a name that can't be a town says
/// so); a changed name also saves when the sheet closes (typing is never lost). The line under each cross-fades as the
/// server follows ("Finding “Bedford”…" → "Forecast for Bedford · from Open-Meteo"), in the accent colour when the name
/// couldn't be found. Synced.
struct WeatherPlaceSection: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel("Weather", palette)
            Text("Home and work for Today's weather line, the brief and Ask. Only the names are sent to Open-Meteo.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
            PlaceField(
                label: "Home", choice: model.weather?.placeChoice, fallback: WeatherPlaceRules.shared.HOME,
                fallbackLine: "Forecast for \(WeatherPlaceRules.shared.HOME) · from Open-Meteo",
                isDefault: { WeatherPlaceRules.shared.isHome(name: $0) }, resetLabel: "Use home",
                spokenLabel: "Weather for which town", palette: palette,
                save: { name in await model.setWeatherPlace(name) }
            )
            PlaceField(
                label: "Work", choice: model.weather?.workChoice, fallback: PlacesRules.shared.WORK,
                fallbackLine: PlacesRules.shared.workLine(place: PlacesRules.shared.WORK),
                isDefault: { PlacesRules.shared.isDefaultWork(name: $0) }, resetLabel: "Use \(PlacesRules.shared.WORK)",
                spokenLabel: "Where work is", palette: palette,
                save: { name in await model.setWorkPlace(name) }
            )
            .padding(.top, MekaSpace.xs)
        }
    }
}

/// One place: its label, the field, and the line under it (with a way back to the default).
private struct PlaceField: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let label: String
    let choice: WeatherPlaceView?
    let fallback: String
    let fallbackLine: String
    let isDefault: (String) -> Bool
    let resetLabel: String
    let spokenLabel: String
    let palette: MekaPalette
    let save: @MainActor (String) async -> Bool
    @State private var text = ""
    @State private var bad = false

    private var savedName: String { choice?.name ?? fallback }
    private var line: String {
        if bad { return "That doesn't look like a town · try Bedford" }
        return choice?.line ?? fallbackLine
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            HStack(spacing: MekaSpace.m) {
                TextField(fallback, text: $text)
                    .textFieldStyle(.roundedBorder).font(MekaType.body).frame(maxWidth: 260)
                    .accessibilityLabel(spokenLabel)
                    .onSubmit { submit() }
                    .onChange(of: text) {
                        bad = false
                        let max = Int(WeatherPlaceRules.shared.MAX_NAME)
                        if text.count > max { text = String(text.prefix(max)) }
                    }
                if !isDefault(savedName) {
                    Button(resetLabel) {
                        MekaHaptics.tick()
                        text = fallback
                        store("")
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
            }
            Text(line).font(MekaType.caption)
                .foregroundStyle(bad ? palette.critical : (choice?.lit == true ? palette.accent : palette.textTertiary))
                .id(line).transition(.opacity)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
        .onAppear { text = savedName }
        .onChange(of: savedName) { text = savedName }
        // Typing is never lost: a changed place saves when the sheet closes, as if Return had been pressed.
        .onDisappear {
            let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if t != savedName && WeatherPlaceRules.shared.accepts(input: t) { store(t) }
        }
    }

    private func submit() {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.isEmpty && !WeatherPlaceRules.shared.accepts(input: t) {
            bad = true
            MekaHaptics.tick()
            return
        }
        store(t)
    }

    private func store(_ name: String) {
        Task { bad = !(await save(name)) }
    }
}
