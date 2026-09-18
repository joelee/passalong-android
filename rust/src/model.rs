//! The item as the app sees it: the core's [`ItemMeta`], flattened for the
//! FFI, with the kind label the CLI shows.

use passalong_core::model::{ItemKind, ItemMeta};

/// One stored item's metadata, as `meta.json` describes it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Item {
    /// The id, `<time>-<content key>`.
    pub id: String,
    /// `text`, `file`, or `image` for a clipboard image, as the CLI labels
    /// it.
    pub kind: String,
    /// The file name, `None` for text.
    pub name: Option<String>,
    /// The MIME type.
    pub mime: String,
    /// Content length in bytes.
    pub size: u64,
    /// SHA-256 of the content, hex.
    pub sha256: String,
    /// Creation time, seconds since the Unix epoch.
    pub created_at: i64,
    /// The sending device.
    pub device: String,
    /// The first 80 characters of a text item, `None` for files.
    pub preview: Option<String>,
}

impl From<&ItemMeta> for Item {
    fn from(meta: &ItemMeta) -> Self {
        Self {
            id: meta.id.to_string(),
            kind: kind_label(meta).to_owned(),
            name: meta.name.clone(),
            mime: meta.mime.clone(),
            size: meta.size,
            sha256: meta.sha256.clone(),
            created_at: meta.created_at.timestamp(),
            device: meta.device.clone(),
            preview: meta.preview.clone(),
        }
    }
}

/// The kind shown to people, as `passalong list` shows it: `image` for
/// clipboard images, which are stored as files, otherwise the item's kind.
pub fn kind_label(meta: &ItemMeta) -> &'static str {
    if meta.is_clipboard_image() {
        "image"
    } else {
        match meta.kind {
            ItemKind::Text => "text",
            ItemKind::File => "file",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use passalong_core::model::{ContentHasher, NewItem, preview_of};

    fn meta(item: NewItem, bytes: &[u8]) -> ItemMeta {
        let mut hasher = ContentHasher::new();
        hasher.update(bytes);
        let preview = item
            .name
            .is_none()
            .then(|| preview_of(&String::from_utf8_lossy(bytes)));
        let at = "2026-09-12T09:53:11Z".parse().unwrap();
        item.finish(at, &hasher.finalize(), preview).unwrap()
    }

    #[test]
    fn text_files_and_clipboard_images_are_labelled_as_the_cli_does() {
        let text = meta(NewItem::text("box"), b"hello");
        let item = Item::from(&text);
        assert_eq!(item.kind, "text");
        assert_eq!(item.preview.as_deref(), Some("hello"));
        assert_eq!(item.name, None);
        assert_eq!(item.size, 5);
        assert_eq!(item.created_at, 1_789_206_791);
        assert_eq!(item.id, text.id.to_string());
        assert_eq!(item.sha256, text.sha256);
        assert_eq!(item.device, "box");
        assert_eq!(item.mime, "text/plain; charset=utf-8");

        let file = meta(NewItem::file("report.pdf", "laptop"), b"%PDF");
        let item = Item::from(&file);
        assert_eq!(item.kind, "file");
        assert_eq!(item.name.as_deref(), Some("report.pdf"));
        assert_eq!(item.mime, "application/pdf");

        let at = "2026-09-12T09:54:11Z".parse().unwrap();
        let image = meta(NewItem::clipboard_image("laptop", at), b"png");
        assert_eq!(Item::from(&image).kind, "image");
        assert_eq!(
            Item::from(&image).name.as_deref(),
            Some("clipboard-20260912-095411.png")
        );
    }
}
