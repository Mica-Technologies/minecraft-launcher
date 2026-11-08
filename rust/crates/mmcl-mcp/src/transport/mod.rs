// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Transports (Java `transport/`): the [`guard`] that admits a request, and the [`http`]
//! loopback listener. The stdio relay lives in [`crate::relay`].

pub mod guard;
pub mod http;
