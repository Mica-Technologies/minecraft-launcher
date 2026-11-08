// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! OpenRGB over its SDK TCP protocol (Java `OpenRgbBackend`). Pure Rust, every platform.

pub mod proto;

use std::collections::HashMap;
use std::io::BufWriter;
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::Duration;

use mmcl_i18n::{t, tf};

use crate::backend::{BackendKind, RgbBackend};
use crate::error::{RgbError, RgbResult};
use crate::frame::RgbFrame;
use crate::key::KeyboardKey;

pub const DEFAULT_HOST: &str = "localhost";
pub const DEFAULT_PORT: u16 = 6742;
pub const PROBE_TIMEOUT: Duration = Duration::from_millis(500);
pub const CONNECT_TIMEOUT: Duration = Duration::from_millis(2000);
pub const READ_TIMEOUT: Duration = Duration::from_millis(1000);
/// The client name shown in OpenRGB's SDK client list.
pub const CLIENT_NAME: &str = "Mica Minecraft Launcher";

#[derive(Debug, Clone)]
struct Device {
    index: u32,
    num_leds: usize,
    key_map: HashMap<KeyboardKey, usize>,
}

/// The OpenRGB backend.
pub struct OpenRgbBackend {
    host: String,
    port: u16,
    stream: Option<TcpStream>,
    devices: Vec<Device>,
}

impl OpenRgbBackend {
    /// The default server, `localhost:6742`.
    pub fn new() -> OpenRgbBackend {
        OpenRgbBackend::with_server(DEFAULT_HOST, DEFAULT_PORT)
    }

    /// Another server (tests).
    pub fn with_server(host: &str, port: u16) -> OpenRgbBackend {
        OpenRgbBackend {
            host: host.to_string(),
            port,
            stream: None,
            devices: Vec::new(),
        }
    }

    fn addrs(&self) -> Vec<SocketAddr> {
        (self.host.as_str(), self.port)
            .to_socket_addrs()
            .map(|a| a.collect())
            .unwrap_or_default()
    }

    fn connect(&self, timeout: Duration) -> RgbResult<TcpStream> {
        let mut last = None;
        for a in self.addrs() {
            match TcpStream::connect_timeout(&a, timeout) {
                Ok(s) => return Ok(s),
                Err(e) => last = Some(e),
            }
        }
        Err(last.map(RgbError::Io).unwrap_or_else(|| {
            RgbError::Unavailable(format!("{}:{} did not resolve", self.host, self.port))
        }))
    }

    /// Number of registered devices.
    pub fn device_count(&self) -> usize {
        self.devices.len()
    }

    fn handshake(&mut self, stream: &mut TcpStream) -> RgbResult<()> {
        let mut name = CLIENT_NAME.as_bytes().to_vec();
        name.push(0);
        proto::write_packet(stream, 0, proto::SET_CLIENT_NAME, &name)?;
        proto::write_packet(stream, 0, proto::REQUEST_CONTROLLER_COUNT, &[])?;
        let body = proto::read_packet(stream, proto::REQUEST_CONTROLLER_COUNT)?;
        if body.len() < 4 {
            return Err(RgbError::protocol(
                "OpenRGB controller count reply too short",
            ));
        }
        let count = u32::from_le_bytes([body[0], body[1], body[2], body[3]]);
        for i in 0..count {
            proto::write_packet(
                stream,
                i,
                proto::REQUEST_CONTROLLER_DATA,
                &proto::PROTOCOL_VERSION.to_le_bytes(),
            )?;
            let body = proto::read_packet(stream, proto::REQUEST_CONTROLLER_DATA)?;
            let data = match proto::parse_controller_data(&body) {
                Ok(d) => d,
                Err(e) => {
                    tracing::warn!(
                        "{}: {e}",
                        tf!("log.rgb.openrgb.parseDeviceFailed", i.to_string())
                    );
                    continue;
                }
            };
            if data.led_names.is_empty() {
                continue;
            }
            let _ = proto::write_packet(stream, i, proto::RGBCONTROLLER_SETCUSTOMMODE, &[]);
            let key_map = if data.device_type == proto::DEVICE_TYPE_KEYBOARD {
                proto::led_name_map(&data.led_names)
            } else {
                HashMap::new()
            };
            let suffix = if key_map.is_empty() {
                String::new()
            } else {
                tf!(
                    "log.rgb.openrgb.mappedKeysSuffix",
                    key_map.len().to_string()
                )
            };
            tracing::info!(
                "{}",
                tf!(
                    "log.rgb.openrgb.registeredDevice",
                    proto::device_type_label(data.device_type),
                    i.to_string(),
                    data.name.as_str(),
                    data.led_names.len().to_string(),
                    suffix
                )
            );
            self.devices.push(Device {
                index: i,
                num_leds: data.led_names.len(),
                key_map,
            });
        }
        if self.devices.is_empty() {
            tracing::info!("{}", t!("log.rgb.openrgb.noUsableDevices"));
        }
        Ok(())
    }

    fn send_all(&mut self, frame: Option<&RgbFrame>) -> RgbResult<()> {
        let Some(stream) = self.stream.as_mut() else {
            return Ok(());
        };
        let mut w = BufWriter::new(stream);
        for d in &self.devices {
            let colors = device_colors(d, frame);
            proto::write_packet(
                &mut w,
                d.index,
                proto::RGBCONTROLLER_UPDATELEDS,
                &proto::update_leds_body(&colors),
            )?;
        }
        Ok(())
    }
}

impl Default for OpenRgbBackend {
    fn default() -> Self {
        OpenRgbBackend::new()
    }
}

/// The LED colours for one device (`None` = all black).
fn device_colors(d: &Device, frame: Option<&RgbFrame>) -> Vec<u32> {
    let Some(frame) = frame else {
        return vec![0; d.num_leds];
    };
    let mut colors = vec![frame.background().pack_rgb(); d.num_leds];
    for (key, idx) in &d.key_map {
        if let Some(c) = frame.override_for(*key)
            && *idx < colors.len()
        {
            colors[*idx] = c.pack_rgb();
        }
    }
    colors
}

impl RgbBackend for OpenRgbBackend {
    fn name(&self) -> &str {
        BackendKind::OpenRgb.label()
    }

    fn is_available(&mut self) -> bool {
        self.connect(PROBE_TIMEOUT).is_ok()
    }

    fn start(&mut self) -> RgbResult<()> {
        self.shutdown();
        let mut stream = self.connect(CONNECT_TIMEOUT)?;
        stream.set_read_timeout(Some(READ_TIMEOUT))?;
        stream.set_write_timeout(Some(READ_TIMEOUT))?;
        let _ = stream.set_nodelay(true);
        if let Err(e) = self.handshake(&mut stream) {
            self.devices.clear();
            return Err(e);
        }
        self.stream = Some(stream);
        Ok(())
    }

    fn render_frame(&mut self, frame: &RgbFrame) -> RgbResult<()> {
        self.send_all(Some(frame))
    }

    fn shutdown(&mut self) {
        let _ = self.send_all(None);
        if let Some(s) = self.stream.take() {
            let _ = s.shutdown(std::net::Shutdown::Both);
        }
        self.devices.clear();
    }

    fn kind(&self) -> BackendKind {
        BackendKind::OpenRgb
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::color::RgbColor;
    use std::io::Read;
    use std::net::TcpListener;

    /// A one-connection fake OpenRGB server with one keyboard; returns the UPDATELEDS bodies.
    fn fake_server(listener: TcpListener, frames: usize) -> std::thread::JoinHandle<Vec<Vec<u8>>> {
        std::thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let mut out = Vec::new();
            loop {
                let mut h = [0u8; 16];
                if s.read_exact(&mut h).is_err() {
                    break;
                }
                let dev = u32::from_le_bytes(h[4..8].try_into().unwrap());
                let id = u32::from_le_bytes(h[8..12].try_into().unwrap());
                let len = u32::from_le_bytes(h[12..16].try_into().unwrap()) as usize;
                let mut body = vec![0; len];
                s.read_exact(&mut body).unwrap();
                match id {
                    proto::REQUEST_CONTROLLER_COUNT => {
                        proto::write_packet(&mut s, 0, id, &1u32.to_le_bytes()).unwrap()
                    }
                    proto::REQUEST_CONTROLLER_DATA => {
                        assert_eq!(dev, 0);
                        let blob = proto::fixture::keyboard(&["Key: W", "Key: A", "Key: Escape"]);
                        proto::write_packet(&mut s, 0, id, &blob).unwrap()
                    }
                    proto::RGBCONTROLLER_UPDATELEDS => {
                        out.push(body);
                        if out.len() == frames {
                            break;
                        }
                    }
                    _ => {}
                }
            }
            out
        })
    }

    #[test]
    fn talks_to_a_fake_server() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = fake_server(listener, 2);
        let mut b = OpenRgbBackend::with_server("127.0.0.1", port);
        b.start().unwrap();
        assert_eq!(b.device_count(), 1);
        let red = RgbColor::new(255, 0, 0);
        let white = RgbColor::WHITE;
        b.render_frame(&RgbFrame::new(red, [(KeyboardKey::A, white)]))
            .unwrap();
        b.shutdown();
        let bodies = server.join().unwrap();
        assert_eq!(bodies.len(), 2);
        assert_eq!(
            &bodies[0][6..],
            &[255, 0, 0, 0, 255, 255, 255, 0, 255, 0, 0, 0]
        );
        assert_eq!(&bodies[1][6..], &[0; 12], "shutdown paints black");
        b.shutdown(); // idempotent
    }

    #[test]
    fn closed_port_is_unavailable() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        drop(listener);
        let mut b = OpenRgbBackend::with_server("127.0.0.1", port);
        assert!(!b.is_available());
        assert!(b.start().is_err());
        assert!(
            b.render_frame(&RgbFrame::solid(RgbColor::BLACK)).is_ok(),
            "no session = no-op"
        );
    }
}
