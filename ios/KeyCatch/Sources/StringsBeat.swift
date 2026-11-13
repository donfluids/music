// The beat switch is on the iPhone only for now. When the Android app gets it, move these into
// app/src/main/res/values/strings.xml (limits_beat and limits_thanks take the new wording) and
// regenerate Strings.swift with tools/ios/strings.py; then delete this file.

extension S {
    static let settingsBeat = "Tempo and time signature"
    static let beatStandard = "Standard"
    static let beatBeatThis = "Accurate"
    static let settingsBeatHelp = "In Band and Guitar modes, Accurate follows the beat with a learned model (Beat This!). It gets the tempo and the time signature right more often, and the phone works harder. If Details says the phone is falling behind, choose Standard."
    static let limitsBeatWithModel = "In Band and Guitar modes the tempo can lock onto double or half the real speed. Tap the BPM number to switch between them. A song in 6/8 or 12/8 often shows as 2/4 or 4/4."
    static let limitsThanksWithBeat = "KeyCatch listens with three open research models: CREPE, by Jong Wook Kim, Justin Salamon, Peter Li and Juan Pablo Bello (MIT License), Basic Pitch, by Spotify's Audio Intelligence Lab (Apache 2.0), and Beat This!, by Francesco Foscarin, Jan Schlüter and Gerhard Widmer (MIT License). All three run on your iPhone through Core ML. Text is set in Manrope (SIL Open Font License). Their licenses ship with the app."
}
