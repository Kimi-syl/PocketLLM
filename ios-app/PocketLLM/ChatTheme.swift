import MarkdownUI
import SwiftUI

extension Theme {
    /// Markdown theme for chat bubbles.
    ///
    /// Built on MarkdownUI's `basic` theme rather than `gitHub`, which paints its
    /// own opaque surfaces and fights the bubble background. Inheriting the
    /// ambient text colour keeps replies legible inside a tinted bubble and in
    /// dark mode; only code blocks get a surface of their own, scrollable
    /// sideways because a wide line inside a bubble has nowhere to go.
    static let chat = Theme.basic
        .codeBlock { configuration in
            ScrollView(.horizontal, showsIndicators: false) {
                Text(configuration.content.trimmingCharacters(in: .whitespacesAndNewlines))
                    .font(.system(size: 13, design: .monospaced))
                    .textSelection(.enabled)
                    .padding(10)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.systemGray6))
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
}
