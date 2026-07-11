// SPDX-License-Identifier: MPL-2.0

use std::process::Command;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NativeVolumeState {
    pub percent: u16,
    pub muted: bool,
}

impl NativeVolumeState {
    pub fn new(percent: u16, muted: bool) -> Self {
        Self {
            percent: percent.min(100),
            muted,
        }
    }
}

pub struct NativeVolume;

impl NativeVolume {
    pub fn read() -> Result<NativeVolumeState, String> {
        read_platform_volume()
    }

    pub fn set(state: NativeVolumeState) -> Result<(), String> {
        set_platform_volume(state)
    }
}

#[cfg(target_os = "linux")]
fn read_platform_volume() -> Result<NativeVolumeState, String> {
    if let Ok(output) = command_output("wpctl", &["get-volume", "@DEFAULT_AUDIO_SINK@"]) {
        if let Some(state) = parse_wpctl(&output) {
            return Ok(state);
        }
    }

    let volume = command_output("pactl", &["get-sink-volume", "@DEFAULT_SINK@"])?;
    let percent = volume
        .split_whitespace()
        .find_map(|part| part.strip_suffix('%')?.parse::<u16>().ok())
        .ok_or_else(|| format!("could not parse pactl volume: {volume}"))?;
    let muted = command_output("pactl", &["get-sink-mute", "@DEFAULT_SINK@"])?.contains("yes");
    Ok(NativeVolumeState::new(percent, muted))
}

#[cfg(target_os = "linux")]
fn set_platform_volume(state: NativeVolumeState) -> Result<(), String> {
    let percent = format!("{}%", state.percent.min(100));
    if command_status("wpctl", &["set-volume", "@DEFAULT_AUDIO_SINK@", &percent]).is_ok() {
        command_status(
            "wpctl",
            &[
                "set-mute",
                "@DEFAULT_AUDIO_SINK@",
                if state.muted { "1" } else { "0" },
            ],
        )?;
        return Ok(());
    }

    command_status("pactl", &["set-sink-volume", "@DEFAULT_SINK@", &percent])?;
    command_status(
        "pactl",
        &[
            "set-sink-mute",
            "@DEFAULT_SINK@",
            if state.muted { "1" } else { "0" },
        ],
    )
}

#[cfg(target_os = "linux")]
fn parse_wpctl(output: &str) -> Option<NativeVolumeState> {
    let value = output.split_whitespace().find_map(|part| {
        let parsed = part.parse::<f32>().ok()?;
        (0.0..=2.0).contains(&parsed).then_some(parsed)
    })?;
    Some(NativeVolumeState::new(
        (value * 100.0).round() as u16,
        output.contains("MUTED"),
    ))
}

#[cfg(target_os = "macos")]
fn read_platform_volume() -> Result<NativeVolumeState, String> {
    let output = command_output(
        "osascript",
        &[
            "-e",
            "output volume of (get volume settings) & \"|\" & output muted of (get volume settings)",
        ],
    )?;
    let mut parts = output.trim().split('|');
    let percent = parts
        .next()
        .and_then(|value| value.trim().parse::<u16>().ok())
        .ok_or_else(|| format!("could not parse macOS output volume: {output}"))?;
    let muted = parts
        .next()
        .map(|value| value.trim().eq_ignore_ascii_case("true"))
        .unwrap_or(false);
    Ok(NativeVolumeState::new(percent, muted))
}

#[cfg(target_os = "macos")]
fn set_platform_volume(state: NativeVolumeState) -> Result<(), String> {
    let script = format!(
        "set volume output volume {} output muted {}",
        state.percent.min(100),
        if state.muted { "true" } else { "false" }
    );
    command_status("osascript", &["-e", &script])
}

#[cfg(target_os = "windows")]
fn read_platform_volume() -> Result<NativeVolumeState, String> {
    let script = format!(
        "{}; $v=[OSR.Audio]::GetVolume(); $m=[OSR.Audio]::GetMute(); Write-Output (([math]::Round($v*100)).ToString() + '|' + $m.ToString())",
        WINDOWS_AUDIO_TYPE
    );
    let output = command_output(
        "powershell.exe",
        &[
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            &script,
        ],
    )?;
    let line = output.lines().last().unwrap_or_default().trim();
    let mut parts = line.split('|');
    let percent = parts
        .next()
        .and_then(|value| value.trim().parse::<u16>().ok())
        .ok_or_else(|| format!("could not parse Windows output volume: {output}"))?;
    let muted = parts
        .next()
        .map(|value| value.trim().eq_ignore_ascii_case("true"))
        .unwrap_or(false);
    Ok(NativeVolumeState::new(percent, muted))
}

#[cfg(target_os = "windows")]
fn set_platform_volume(state: NativeVolumeState) -> Result<(), String> {
    let scalar = state.percent.min(100) as f32 / 100.0;
    let script = format!(
        "{}; [OSR.Audio]::SetVolume({scalar}); [OSR.Audio]::SetMute(${})",
        WINDOWS_AUDIO_TYPE,
        if state.muted { "true" } else { "false" }
    );
    command_status(
        "powershell.exe",
        &[
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            &script,
        ],
    )
}

#[cfg(target_os = "windows")]
const WINDOWS_AUDIO_TYPE: &str = r#"
if (-not ('OSR.Audio' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace OSR {
    enum EDataFlow { eRender, eCapture, eAll, EDataFlow_enum_count }
    enum ERole { eConsole, eMultimedia, eCommunications, ERole_enum_count }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumeratorComObject { }

    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator {
        int EnumAudioEndpoints(EDataFlow dataFlow, uint stateMask, out IntPtr devices);
        int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice endpoint);
        int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
        int RegisterEndpointNotificationCallback(IntPtr client);
        int UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice {
        int Activate(ref Guid iid, uint clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
        int OpenPropertyStore(uint access, out IntPtr properties);
        int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetState(out uint state);
    }

    [Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioEndpointVolume {
        int RegisterControlChangeNotify(IntPtr notify);
        int UnregisterControlChangeNotify(IntPtr notify);
        int GetChannelCount(out uint channelCount);
        int SetMasterVolumeLevel(float levelDb, Guid eventContext);
        int SetMasterVolumeLevelScalar(float level, Guid eventContext);
        int GetMasterVolumeLevel(out float levelDb);
        int GetMasterVolumeLevelScalar(out float level);
        int SetChannelVolumeLevel(uint channel, float levelDb, Guid eventContext);
        int SetChannelVolumeLevelScalar(uint channel, float level, Guid eventContext);
        int GetChannelVolumeLevel(uint channel, out float levelDb);
        int GetChannelVolumeLevelScalar(uint channel, out float level);
        int SetMute([MarshalAs(UnmanagedType.Bool)] bool muted, Guid eventContext);
        int GetMute([MarshalAs(UnmanagedType.Bool)] out bool muted);
        int GetVolumeStepInfo(out uint step, out uint stepCount);
        int VolumeStepUp(Guid eventContext);
        int VolumeStepDown(Guid eventContext);
        int QueryHardwareSupport(out uint mask);
        int GetVolumeRange(out float minDb, out float maxDb, out float incrementDb);
    }

    public static class Audio {
        static IAudioEndpointVolume Endpoint() {
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
            IMMDevice device;
            Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(EDataFlow.eRender, ERole.eMultimedia, out device));
            Guid iid = typeof(IAudioEndpointVolume).GUID;
            object instance;
            Marshal.ThrowExceptionForHR(device.Activate(ref iid, 23, IntPtr.Zero, out instance));
            return (IAudioEndpointVolume)instance;
        }

        public static float GetVolume() {
            float value;
            Marshal.ThrowExceptionForHR(Endpoint().GetMasterVolumeLevelScalar(out value));
            return value;
        }

        public static bool GetMute() {
            bool value;
            Marshal.ThrowExceptionForHR(Endpoint().GetMute(out value));
            return value;
        }

        public static void SetVolume(float value) {
            Marshal.ThrowExceptionForHR(Endpoint().SetMasterVolumeLevelScalar(Math.Max(0, Math.Min(1, value)), Guid.Empty));
        }

        public static void SetMute(bool value) {
            Marshal.ThrowExceptionForHR(Endpoint().SetMute(value, Guid.Empty));
        }
    }
}
'@
}
"#;

#[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
fn read_platform_volume() -> Result<NativeVolumeState, String> {
    Err("native volume is not implemented for this desktop platform".to_owned())
}

#[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
fn set_platform_volume(_state: NativeVolumeState) -> Result<(), String> {
    Err("native volume is not implemented for this desktop platform".to_owned())
}

fn command_output(program: &str, args: &[&str]) -> Result<String, String> {
    let output = Command::new(program)
        .args(args)
        .output()
        .map_err(|error| format!("failed to run {program}: {error}"))?;
    if !output.status.success() {
        return Err(format!(
            "{program} failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    Ok(String::from_utf8_lossy(&output.stdout).trim().to_owned())
}

fn command_status(program: &str, args: &[&str]) -> Result<(), String> {
    let output = Command::new(program)
        .args(args)
        .output()
        .map_err(|error| format!("failed to run {program}: {error}"))?;
    if output.status.success() {
        Ok(())
    } else {
        Err(format!(
            "{program} failed: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ))
    }
}
