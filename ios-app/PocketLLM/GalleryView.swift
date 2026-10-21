import SwiftUI

/// Everything the image tab has produced, newest first.
///
/// The grid only ever decodes the 512 px thumbnails the store writes beside each
/// PNG; the full image is loaded when a cell is opened.
struct GalleryView: View {
    @EnvironmentObject private var gallery: GalleryStore
    @State private var selected: GalleryStore.Item?

    private let columns = [GridItem(.adaptive(minimum: 108), spacing: 8)]

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 8) {
                    ForEach(gallery.items) { item in
                        Button { selected = item } label: { cell(item) }
                            .buttonStyle(.plain)
                    }
                }
                .padding()
            }
            .navigationTitle("圖庫")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Text("\(gallery.items.count)")
                        .font(.callout.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .overlay {
                if gallery.items.isEmpty {
                    ContentUnavailableView(
                        "還沒有作品",
                        systemImage: "photo.on.rectangle.angled",
                        description: Text("在「圖片生成」分頁產生的圖片會自動存到這裡。"))
                }
            }
            .overlay(alignment: .bottom) { banner }
            .task { gallery.reload() }
            .task(id: gallery.notice) {
                guard gallery.notice != nil else { return }
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                gallery.notice = nil
            }
            .sheet(item: $selected) { item in
                GalleryDetailView(item: item) {
                    selected = nil
                    gallery.delete(item)
                }
            }
        }
    }

    @ViewBuilder
    private var banner: some View {
        if let notice = gallery.notice {
            Text(notice)
                .font(.caption)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(.thinMaterial, in: Capsule())
                .padding(.bottom, 12)
                .onTapGesture { gallery.notice = nil }
        }
    }

    private func cell(_ item: GalleryStore.Item) -> some View {
        GalleryThumb(thumb: item.thumbURL, full: item.url)
            .overlay(alignment: .topTrailing) {
                if item.isUpscaled {
                    Text("\(item.meta.scale ?? 4)×")
                        .font(.caption2.bold())
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(6)
                }
            }
    }
}

private struct GalleryThumb: View {
    let thumb: URL
    let full: URL
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8)
                .fill(Color.secondary.opacity(0.12))
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "photo")
                    .foregroundStyle(.tertiary)
            }
        }
        .frame(height: 108)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .contentShape(RoundedRectangle(cornerRadius: 8))
        .task(id: thumb) {
            // The thumbnail is missing only if the JPEG write failed; the full
            // image still renders, just more expensively.
            let path = FileManager.default.fileExists(atPath: thumb.path) ? thumb : full
            image = UIImage(contentsOfFile: path.path)
        }
    }
}

struct GalleryDetailView: View {
    let item: GalleryStore.Item
    let onDelete: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var image: UIImage?
    @State private var confirmDelete = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    } else {
                        ProgressView()
                            .frame(maxWidth: .infinity, minHeight: 220)
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        row("尺寸", "\(item.meta.width) × \(item.meta.height)")
                        if item.meta.steps > 0 { row("步數", "\(item.meta.steps)") }
                        if item.meta.seed >= 0 { row("Seed", "\(item.meta.seed)") }
                        if !item.meta.model.isEmpty { row("模型", item.meta.model) }
                        if item.isUpscaled { row("放大", "\(item.meta.scale ?? 4)×") }
                        row("時間", item.createdAt.formatted(date: .abbreviated, time: .shortened))
                    }
                    .font(.callout)

                    if !item.meta.prompt.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("提示詞").font(.caption).foregroundStyle(.secondary)
                            Text(item.meta.prompt).font(.callout)
                        }
                    }
                    if !item.meta.negative.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("負向提示詞").font(.caption).foregroundStyle(.secondary)
                            Text(item.meta.negative).font(.callout).foregroundStyle(.secondary)
                        }
                    }
                }
                .padding()
            }
            .navigationTitle(item.isUpscaled ? "放大結果" : "生成結果")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("完成") { dismiss() }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if let image {
                        ShareLink(item: Image(uiImage: image),
                                  preview: SharePreview("image", image: Image(uiImage: image)))
                    }
                    Button(role: .destructive) {
                        confirmDelete = true
                    } label: {
                        Image(systemName: "trash")
                    }
                }
            }
            .task(id: item.id) { image = UIImage(contentsOfFile: item.url.path) }
            .confirmationDialog("刪除這張圖片？", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("刪除", role: .destructive) { onDelete() }
            } message: {
                Text("檔案會從裝置上移除，無法復原。")
            }
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label).foregroundStyle(.secondary)
            Spacer(minLength: 12)
            Text(value)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
                .truncationMode(.middle)
        }
    }
}
