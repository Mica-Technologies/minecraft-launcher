// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Combining several launches' progress into one taskbar/dock value
//! (`TaskbarProgressManager.combine`). The native owner lives in `mmcl-platform::progress`.

/// One progress value.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Progress {
    /// `0.0..=1.0`; larger values are capped, negative ones read as indeterminate.
    Fraction(f64),
    /// Busy without a known fraction.
    Indeterminate,
}

/// The combined value: empty gives `Fraction(0.0)`; any indeterminate (or negative) value
/// makes the whole bar indeterminate; otherwise the mean of the values capped at 1.
///
/// With several launches running, each one used to overwrite the bar and the first to finish
/// cleared it for all; combining fixes both.
pub fn combine(values: &[Progress]) -> Progress {
    if values.is_empty() {
        return Progress::Fraction(0.0);
    }
    let mut sum = 0.0;
    for v in values {
        match *v {
            Progress::Indeterminate => return Progress::Indeterminate,
            Progress::Fraction(f) if f < 0.0 || f.is_nan() => return Progress::Indeterminate,
            Progress::Fraction(f) => sum += f.min(1.0),
        }
    }
    Progress::Fraction(sum / values.len() as f64)
}

#[cfg(test)]
mod tests {
    use super::Progress::*;
    use super::*;

    fn frac(p: Progress) -> f64 {
        match p {
            Fraction(f) => f,
            Indeterminate => panic!("expected a fraction"),
        }
    }

    #[test]
    fn averages_the_launches() {
        assert!((frac(combine(&[Fraction(0.25), Fraction(0.75)])) - 0.5).abs() < 1e-9);
    }

    #[test]
    fn one_launch_is_itself() {
        assert!((frac(combine(&[Fraction(0.4)])) - 0.4).abs() < 1e-9);
    }

    #[test]
    fn any_indeterminate_launch_makes_the_bar_indeterminate() {
        assert_eq!(combine(&[Fraction(0.5), Indeterminate]), Indeterminate);
        assert_eq!(combine(&[Fraction(0.5), Fraction(-1.0)]), Indeterminate);
    }

    #[test]
    fn overshoot_is_capped() {
        assert!((frac(combine(&[Fraction(1.7)])) - 1.0).abs() < 1e-9);
    }

    #[test]
    fn no_launches_is_zero() {
        assert_eq!(combine(&[]), Fraction(0.0));
    }
}
