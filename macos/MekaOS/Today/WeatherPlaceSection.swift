@preconcurrency import MekaKit
import SwiftUI

/// Weather place setting (Calendars → Weather): the town the forecast is for, Biggleswade (home) unless Meka types
/// another. Return saves it (a name that can't be a town says so); a changed name also saves when the sheet closes
/// (typing is never lost). The line under it cross-fades as the server follows ("Finding “Bedford”…" → "Forecast for
/// Bedford · from Open-Meteo"), in the accent colour when the name couldn't be found. Synced.
struct WeatherPlaceSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    @State private var bad = false

    private var choice: WeatherPlaceView? { model.weather?.placeChoice }
    private var savedName: String { choice?.name ?? WeatherPlaceRules.shared.HOME }
    private var line: String {
        if bad { return "That doesn't look like a town · try Bedford" }
        return choice?.line ?? "Forecast for \(WeatherPlaceRules.shared.HOME) · from Open-Meteo"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel("Weather", palette)
            Text("The town Today's weather line, the brief and Ask use. Only the name is sent to Open-Meteo.")
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
            HStack(spacing: MekaSpace.m) {
                TextField(WeatherPlaceRules.shared.HOME, text: $text)
                    .textFieldStyle(.roundedBorder).font(MekaType.body).frame(maxWidth: 260)
                    .accessibilityLabel("Weather for which town")
                    .onSubmit { submit() }
                    .onChange(of: text) {
                        bad = false
                        let max = Int(WeatherPlaceRules.shared.MAX_NAME)
                        if text.count > max { text = String(text.prefix(max)) }
                    }
                if !WeatherPlaceRules.shared.isHome(name: savedName) {
                    Button("Use home") {
                        MekaHaptics.tick()
                        text = WeatherPlaceRules.shared.HOME
                        save("")
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
        // Typing is never lost: a changed town saves when the sheet closes, as if Return had been pressed.
        .onDisappear {
            let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if t != savedName && WeatherPlaceRules.shared.accepts(input: t) { save(t) }
        }
    }

    private func submit() {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.isEmpty && !WeatherPlaceRules.shared.accepts(input: t) {
            bad = true
            MekaHaptics.tick()
            return
        }
        save(t)
    }

    private func save(_ name: String) {
        Task { bad = !(await model.setWeatherPlace(name)) }
    }
}
