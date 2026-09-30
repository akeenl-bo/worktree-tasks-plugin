import AppKit
import UserNotifications

// Worktree Tasks' macOS notifier, compiled on the user's machine by the plugin (MacNotifier.kt).
// Launched with --body it posts one banner and quits; relaunched by a click on a banner, it opens
// that banner's task in the IDE and quits.
final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    private let args = CommandLine.arguments

    private func value(_ flag: String) -> String? {
        guard let index = args.firstIndex(of: flag), index + 1 < args.count else { return nil }
        return args[index + 1]
    }

    func applicationWillFinishLaunching(_ notification: Notification) {
        UNUserNotificationCenter.current().delegate = self
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        guard let body = value("--body") else {
            // Relaunched by a click: didReceive arrives shortly after launch.
            quit(after: 5)
            return
        }
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return self.quit(after: 0) }
            let content = UNMutableNotificationContent()
            content.title = self.value("--title") ?? "Worktree Tasks"
            content.body = body
            if let sound = self.value("--sound"), !sound.isEmpty {
                content.sound = UNNotificationSound(named: UNNotificationSoundName(sound))
            }
            content.userInfo = ["path": self.value("--open") ?? "", "app": self.value("--app") ?? ""]
            let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
            center.add(request) { _ in self.quit(after: 1) }
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        if let path = info["path"] as? String, !path.isEmpty {
            let app = (info["app"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "IntelliJ IDEA"
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/usr/bin/open")
            process.arguments = ["-a", app, path]
            try? process.run()
        }
        completionHandler()
        quit(after: 1)
    }

    private func quit(after seconds: Double) {
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { NSApp.terminate(nil) }
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
