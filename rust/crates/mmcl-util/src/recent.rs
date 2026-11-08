// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `RecentPacks.getRecent`: the ordering behind the jump list, dock menu and tray "recent
//! packs" entries. The pack type and its metadata read belong to the modpack crate; this is
//! the selection rule.

/// The items that have been played (`last_played_ms > 0`), most recent first, at most `max`.
/// An item whose metadata could not be read (`None`) is skipped. `max <= 0` gives nothing.
pub fn most_recent<T>(items: impl IntoIterator<Item = (T, Option<i64>)>, max: i32) -> Vec<T> {
    if max <= 0 {
        return Vec::new();
    }
    let mut played: Vec<(T, i64)> = items
        .into_iter()
        .filter_map(|(item, last)| last.filter(|&ms| ms > 0).map(|ms| (item, ms)))
        .collect();
    // Stable sort keeps the installed order for equal timestamps.
    played.sort_by_key(|p| std::cmp::Reverse(p.1));
    played.truncate(max as usize);
    played.into_iter().map(|(item, _)| item).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn orders_filters_and_truncates() {
        let items = vec![
            ("a", Some(10)),
            ("never", Some(0)),
            ("b", Some(30)),
            ("unreadable", None),
            ("c", Some(20)),
        ];
        assert_eq!(most_recent(items.clone(), 2), ["b", "c"]);
        assert_eq!(most_recent(items.clone(), 10), ["b", "c", "a"]);
        assert!(most_recent(items, 0).is_empty());
    }
}
