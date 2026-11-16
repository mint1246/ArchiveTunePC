import SwiftUI
import ArchiveTuneShared

@main
struct ArchiveTuneApp: App {
    var body: some Scene {
        WindowGroup {
            ArchiveTuneView().ignoresSafeArea()
        }
    }
}

private struct ArchiveTuneView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
