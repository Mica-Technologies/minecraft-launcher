// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pure filter / sort / pagination state shared by Home and Browse (`LibraryViewModel`),
//! the option ids of both screens and their labels (`FilterOptionLabels`).
//!
//! The view model is plain data plus a change callback. The 120 ms search debounce is driven by
//! the owning view: [`LibraryViewModel::set_search_query`] returns [`Change::Debounced`] and the
//! view (re)starts its debounce task, which then calls the rebuild.

use std::collections::HashMap;

use mmcl_i18n::t;

/// The search debounce (`DEFAULT_SEARCH_DEBOUNCE_MS`).
pub const DEFAULT_SEARCH_DEBOUNCE_MS: u64 = 120;

/// The label key prefix of type options.
pub const TYPE_PREFIX: &str = "filter.type.";
/// The label key prefix of status options.
pub const STATUS_PREFIX: &str = "filter.status.";
/// The label key prefix of sort options.
pub const SORT_PREFIX: &str = "filter.sort.";

/// Home: type options (default `all`).
pub const HOME_TYPES: [&str; 3] = ["all", "modpacks", "vanilla"];
/// Home: sort options (default `lastPlayed`).
pub const HOME_SORTS: [&str; 5] = [
    "lastPlayed",
    "mostPlayed",
    "nameAz",
    "nameZa",
    "recentUpdate",
];
/// Home: page sizes (default 48).
pub const HOME_PAGE_SIZES: [usize; 5] = [12, 24, 48, 72, 96];
/// Home: the default page size.
pub const HOME_DEFAULT_PAGE_SIZE: usize = 48;

/// Browse: type options (default `all`).
pub const BROWSE_TYPES: [&str; 9] = [
    "all",
    "modpacks",
    "vanilla.release",
    "vanilla.snapshot",
    "vanilla.beta",
    "vanilla.alpha",
    "forge",
    "neoforge",
    "fabric",
];
/// Browse: status options (default `installed`).
pub const BROWSE_STATUSES: [&str; 3] = ["all", "installed", "available"];
/// Browse: sort options (default `default` = collection order).
pub const BROWSE_SORTS: [&str; 7] = [
    "default",
    "nameAz",
    "nameZa",
    "releaseDate",
    "lastPlayed",
    "mostPlayed",
    "recentUpdate",
];
/// Browse: page sizes (default 48).
pub const BROWSE_PAGE_SIZES: [usize; 4] = [24, 48, 72, 96];
/// Browse: the default page size.
pub const BROWSE_DEFAULT_PAGE_SIZE: usize = 48;

/// Filter keys.
pub const FILTER_TYPE: &str = "type";
/// Browse status filter key.
pub const FILTER_STATUS: &str = "status";
/// Home "recently updated only" chip.
pub const FILTER_UPDATES_ONLY: &str = "updatesOnly";

/// The localization key of an option (`FilterOptionLabels.key`).
pub fn label_key(prefix: &str, id: &str) -> String {
    format!("{prefix}{id}")
}

/// The label of an option; `None` (no selection) renders `""` (`FilterOptionLabels.converter`).
pub fn label(prefix: &str, id: Option<&str>) -> String {
    match id {
        None => String::new(),
        Some(id) => t!(&label_key(prefix, id)).into_owned(),
    }
}

/// A filter value.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FilterValue {
    /// A string option.
    Str(String),
    /// A toggle.
    Bool(bool),
}

/// The slice of a list one page shows.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PageBounds {
    /// Items in the whole list.
    pub total_items: usize,
    /// Pages (at least 1).
    pub total_pages: usize,
    /// First index shown.
    pub start: usize,
    /// One past the last index shown.
    pub end: usize,
}

impl PageBounds {
    /// Whether the slice is empty.
    pub fn is_empty(&self) -> bool {
        self.start >= self.end
    }
}

/// What a state change asks of the owner.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Change {
    /// Nothing changed.
    None,
    /// Rebuild now.
    Rebuild,
    /// Rebuild after the search debounce (restart the timer).
    Debounced,
}

/// Rejected construction arguments.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
#[error("page size must be positive")]
pub struct InvalidPageSize;

/// Filter, sort and pagination state (`LibraryViewModel`).
#[derive(Debug, Clone, PartialEq)]
pub struct LibraryViewModel {
    page_size: usize,
    current_page: usize,
    search_query: String,
    sort_key: String,
    filters: HashMap<String, FilterValue>,
    debounce_ms: u64,
    disposed: bool,
}

impl LibraryViewModel {
    /// A model with `page_size` (> 0) and the search debounce.
    pub fn new(page_size: usize, debounce_ms: u64) -> Result<Self, InvalidPageSize> {
        if page_size == 0 {
            return Err(InvalidPageSize);
        }
        Ok(LibraryViewModel {
            page_size,
            current_page: 1,
            search_query: String::new(),
            sort_key: String::new(),
            filters: HashMap::new(),
            debounce_ms,
            disposed: false,
        })
    }

    /// The page size.
    pub fn page_size(&self) -> usize {
        self.page_size
    }

    /// The 1-based page.
    pub fn current_page(&self) -> usize {
        self.current_page
    }

    /// The raw search text.
    pub fn search_query(&self) -> &str {
        &self.search_query
    }

    /// The sort id.
    pub fn sort_key(&self) -> &str {
        &self.sort_key
    }

    /// The search debounce.
    pub fn debounce_ms(&self) -> u64 {
        self.debounce_ms
    }

    /// Whether [`LibraryViewModel::dispose`] ran (changes then report nothing).
    pub fn is_disposed(&self) -> bool {
        self.disposed
    }

    fn fire(&self, change: Change) -> Change {
        if self.disposed { Change::None } else { change }
    }

    /// Sets the page size; a change resets to page 1.
    pub fn set_page_size(&mut self, size: usize) -> Change {
        if size == 0 || size == self.page_size {
            return Change::None;
        }
        self.page_size = size;
        self.current_page = 1;
        self.fire(Change::Rebuild)
    }

    /// Sets the sort id; a change resets to page 1.
    pub fn set_sort_key(&mut self, key: Option<&str>) -> Change {
        let key = key.unwrap_or("");
        if key == self.sort_key {
            return Change::None;
        }
        self.sort_key = key.to_string();
        self.current_page = 1;
        self.fire(Change::Rebuild)
    }

    /// Sets the search text; a change resets to page 1 and debounces.
    pub fn set_search_query(&mut self, query: Option<&str>) -> Change {
        let query = query.unwrap_or("");
        if query == self.search_query {
            return Change::None;
        }
        self.search_query = query.to_string();
        self.current_page = 1;
        self.fire(Change::Debounced)
    }

    /// Sets a filter; a change resets to page 1.
    pub fn set_filter(&mut self, key: &str, value: FilterValue) -> Change {
        let prev = self.filters.insert(key.to_string(), value.clone());
        if prev.as_ref() == Some(&value) {
            return Change::None;
        }
        self.current_page = 1;
        self.fire(Change::Rebuild)
    }

    /// Sets the page without a rebuild (`max(1, p)`).
    pub fn set_current_page(&mut self, page: usize) {
        self.current_page = page.max(1);
    }

    /// One page back (never below 1).
    pub fn prev_page(&mut self) -> Change {
        if self.current_page > 1 {
            self.current_page -= 1;
            self.fire(Change::Rebuild)
        } else {
            Change::None
        }
    }

    /// One page forward (unclamped; [`LibraryViewModel::clamp_and_slice`] fixes overshoot).
    pub fn next_page(&mut self) -> Change {
        self.current_page += 1;
        self.fire(Change::Rebuild)
    }

    /// Clamps the page into range for `total` items and returns its slice.
    pub fn clamp_and_slice(&mut self, total: usize) -> PageBounds {
        let total_pages = total.div_ceil(self.page_size).max(1);
        self.current_page = self.current_page.clamp(1, total_pages);
        let start = (self.current_page - 1) * self.page_size;
        let end = total.min(start + self.page_size);
        PageBounds {
            total_items: total,
            total_pages,
            start: start.min(total),
            end,
        }
    }

    /// The search split on whitespace, lower-cased; empty when blank.
    pub fn search_tokens(&self) -> Vec<String> {
        search_tokens(&self.search_query)
    }

    /// A string filter, or `fallback` when missing or not a string.
    pub fn get_string_filter<'a>(&'a self, key: &str, fallback: &'a str) -> &'a str {
        match self.filters.get(key) {
            Some(FilterValue::Str(s)) => s,
            _ => fallback,
        }
    }

    /// A toggle filter, or `fallback` when missing or not a toggle.
    pub fn get_bool_filter(&self, key: &str, fallback: bool) -> bool {
        match self.filters.get(key) {
            Some(FilterValue::Bool(b)) => *b,
            _ => fallback,
        }
    }

    /// Stops reporting changes (the owner drops its debounce task).
    pub fn dispose(&mut self) {
        self.disposed = true;
    }
}

/// `query.trim().to_lowercase()` split on whitespace.
pub fn search_tokens(query: &str) -> Vec<String> {
    query
        .trim()
        .to_lowercase()
        .split_whitespace()
        .map(str::to_string)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    // Port of LibraryViewModelTest.

    #[test]
    fn rejects_non_positive_page_size() {
        assert_eq!(LibraryViewModel::new(0, 0), Err(InvalidPageSize));
        assert!(LibraryViewModel::new(1, 0).is_ok());
    }

    #[test]
    fn page_size_change_resets_page_and_fires_once() {
        let mut vm = LibraryViewModel::new(10, 0).unwrap();
        vm.set_current_page(4);
        assert_eq!(vm.set_page_size(20), Change::Rebuild);
        assert_eq!(vm.current_page(), 1);
        assert_eq!(vm.set_page_size(20), Change::None);
        assert_eq!(vm.set_page_size(0), Change::None);
        assert_eq!(vm.page_size(), 20);
    }

    #[test]
    fn sort_change_resets_page_and_fires_only_on_difference() {
        let mut vm = LibraryViewModel::new(10, 0).unwrap();
        vm.set_current_page(3);
        assert_eq!(vm.set_sort_key(Some("nameAz")), Change::Rebuild);
        assert_eq!(vm.current_page(), 1);
        vm.set_current_page(3);
        assert_eq!(vm.set_sort_key(Some("nameAz")), Change::None);
        assert_eq!(vm.current_page(), 3);
        assert_eq!(vm.set_sort_key(None), Change::Rebuild);
        assert_eq!(vm.sort_key(), "");
        assert_eq!(vm.set_sort_key(None), Change::None);
    }

    #[test]
    fn filter_map_distinguishes_types_and_falls_back_on_mismatch() {
        let mut vm = LibraryViewModel::new(10, 0).unwrap();
        assert_eq!(
            vm.set_filter("type", FilterValue::Str("vanilla".into())),
            Change::Rebuild
        );
        assert_eq!(
            vm.set_filter("type", FilterValue::Str("vanilla".into())),
            Change::None
        );
        vm.set_filter("updatesOnly", FilterValue::Bool(true));
        assert_eq!(vm.get_string_filter("type", "all"), "vanilla");
        assert!(vm.get_bool_filter("updatesOnly", false));
        // Wrong type -> fallback.
        assert_eq!(vm.get_string_filter("updatesOnly", "x"), "x");
        assert!(vm.get_bool_filter("type", true));
        // Missing -> fallback.
        assert_eq!(vm.get_string_filter("nope", "all"), "all");
        assert!(!vm.get_bool_filter("nope", false));
    }

    #[test]
    fn prev_page_bounded_at_one_without_firing() {
        let mut vm = LibraryViewModel::new(10, 0).unwrap();
        assert_eq!(vm.prev_page(), Change::None);
        assert_eq!(vm.current_page(), 1);
        assert_eq!(vm.next_page(), Change::Rebuild);
        assert_eq!(vm.prev_page(), Change::Rebuild);
        assert_eq!(vm.current_page(), 1);
    }

    #[test]
    fn clamp_and_slice_corrects_out_of_range_pages() {
        let mut vm = LibraryViewModel::new(10, 0).unwrap();
        vm.set_current_page(9);
        let b = vm.clamp_and_slice(25);
        assert_eq!(vm.current_page(), 3);
        assert_eq!(
            b,
            PageBounds {
                total_items: 25,
                total_pages: 3,
                start: 20,
                end: 25
            }
        );
        let b = vm.clamp_and_slice(0);
        assert_eq!(b.total_pages, 1);
        assert_eq!(vm.current_page(), 1);
        assert!(b.is_empty());
        vm.set_current_page(0);
        assert_eq!(vm.current_page(), 1);
    }

    #[test]
    fn search_tokens_split_on_whitespace_and_lowercase() {
        let mut vm = LibraryViewModel::new(10, 0).unwrap();
        assert!(vm.search_tokens().is_empty());
        assert_eq!(
            vm.set_search_query(Some("  Foo\tBAR  baz ")),
            Change::Debounced
        );
        assert_eq!(vm.search_tokens(), vec!["foo", "bar", "baz"]);
        assert_eq!(vm.set_search_query(Some("  Foo\tBAR  baz ")), Change::None);
        vm.set_search_query(Some("   "));
        assert!(vm.search_tokens().is_empty());
    }

    #[test]
    fn disposed_model_reports_nothing() {
        let mut vm = LibraryViewModel::new(10, 0).unwrap();
        vm.dispose();
        assert_eq!(vm.set_sort_key(Some("x")), Change::None);
        assert_eq!(vm.next_page(), Change::None);
    }

    // Port of FilterOptionLabelsTest.

    #[test]
    fn every_option_id_has_a_label() {
        let groups: [(&str, &[&str]); 3] = [
            (
                TYPE_PREFIX,
                &[
                    "all",
                    "modpacks",
                    "vanilla",
                    "vanilla.release",
                    "vanilla.snapshot",
                    "vanilla.beta",
                    "vanilla.alpha",
                    "forge",
                    "neoforge",
                    "fabric",
                ],
            ),
            (STATUS_PREFIX, &BROWSE_STATUSES),
            (SORT_PREFIX, &BROWSE_SORTS),
        ];
        for (prefix, ids) in groups {
            for id in ids {
                assert!(
                    mmcl_i18n::english(&label_key(prefix, id)).is_some(),
                    "missing label for {prefix}{id}"
                );
            }
        }
        for id in HOME_TYPES.iter().chain(BROWSE_TYPES.iter()) {
            assert!(mmcl_i18n::english(&label_key(TYPE_PREFIX, id)).is_some());
        }
        for id in HOME_SORTS.iter() {
            assert!(mmcl_i18n::english(&label_key(SORT_PREFIX, id)).is_some());
        }
    }

    #[test]
    fn a_null_selection_renders_empty() {
        assert_eq!(label(SORT_PREFIX, None), "");
    }
}
