import SwiftUI

/// The Android app's palette: one accent (teal) used sparingly, one signal colour (coral) for
/// the downbeat and recording, and warm neutrals. Light values were checked against WCAG.
struct Palette {
    let primary, onPrimary, primaryContainer, onPrimaryContainer: Color
    let secondaryContainer, onSecondaryContainer: Color
    let surfaceContainerHigh: Color
    let tertiary, onTertiary, tertiaryContainer, onTertiaryContainer: Color
    let background, onBackground, surface, onSurface: Color
    let surfaceVariant, onSurfaceVariant, outline, outlineVariant: Color

    static let light = Palette(
        primary: Color(hex: 0x0F766E), onPrimary: .white, primaryContainer: Color(hex: 0xD9F0EC), onPrimaryContainer: Color(hex: 0x0B3F3B),
        secondaryContainer: Color(hex: 0xE6E2D9), onSecondaryContainer: Color(hex: 0x1C1B19),
        surfaceContainerHigh: Color(hex: 0xEDEAE4),
        tertiary: Color(hex: 0xB8492A), onTertiary: .white, tertiaryContainer: Color(hex: 0xFBE3DC), onTertiaryContainer: Color(hex: 0x7A2B14),
        background: Color(hex: 0xFAF9F6), onBackground: Color(hex: 0x1C1B19), surface: .white, onSurface: Color(hex: 0x1C1B19),
        surfaceVariant: Color(hex: 0xDCD8CF), onSurfaceVariant: Color(hex: 0x5E5953), outline: Color(hex: 0x8C877F), outlineVariant: Color(hex: 0xE8E5DE))

    static let dark = Palette(
        primary: Color(hex: 0x5EEAD4), onPrimary: Color(hex: 0x00332E), primaryContainer: Color(hex: 0x134E4A), onPrimaryContainer: Color(hex: 0xCCFBF1),
        secondaryContainer: Color(hex: 0x2F3437), onSecondaryContainer: Color(hex: 0xECEAE4),
        surfaceContainerHigh: Color(hex: 0x25282A),
        tertiary: Color(hex: 0xF2997F), onTertiary: Color(hex: 0x3B1409), tertiaryContainer: Color(hex: 0x5C2A1A), onTertiaryContainer: Color(hex: 0xFFD9CE),
        background: Color(hex: 0x141516), onBackground: Color(hex: 0xECEAE4), surface: Color(hex: 0x1E2022), onSurface: Color(hex: 0xECEAE4),
        surfaceVariant: Color(hex: 0x2E3235), onSurfaceVariant: Color(hex: 0xA8A39B), outline: Color(hex: 0x6B7076), outlineVariant: Color(hex: 0x2E3134))
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: 1)
    }
}

private struct PaletteKey: EnvironmentKey { static let defaultValue = Palette.light }

extension EnvironmentValues {
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}

/// Picks the palette for the colour scheme in force, so every view reads `\.palette`.
struct PaletteProvider: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    func body(content: Content) -> some View { content.environment(\.palette, scheme == .dark ? .dark : .light) }
}

/// Manrope, bundled so the app looks the same on every phone (SIL Open Font License 1.1), in
/// the Android app's sizes. Sizes follow Dynamic Type through `relativeTo`.
enum AppFont {
    enum Weight { case regular, medium, semibold }

    static func manrope(_ size: CGFloat, _ weight: Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        let name: String
        switch weight {
        case .regular: name = "Manrope-Regular"
        case .medium: name = "Manrope-Medium"
        case .semibold: name = "Manrope-SemiBold"
        }
        return .custom(name, size: size, relativeTo: style)
    }

    static let displayLarge = manrope(56, .semibold, relativeTo: .largeTitle)
    static let headlineSmall = manrope(24, .semibold, relativeTo: .title2)
    static let titleLarge = manrope(20, .semibold, relativeTo: .title3)
    static let titleMedium = manrope(16, .medium, relativeTo: .headline)
    static let bodyLarge = manrope(16, .regular, relativeTo: .body)
    static let bodyMedium = manrope(14, .regular, relativeTo: .subheadline)
    static let bodySmall = manrope(12, .regular, relativeTo: .footnote)
    static let labelLarge = manrope(14, .medium, relativeTo: .subheadline)
    static let labelMedium = manrope(12, .medium, relativeTo: .caption)
    static let labelSmall = manrope(11, .medium, relativeTo: .caption2)
}
