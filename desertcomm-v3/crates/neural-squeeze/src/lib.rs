//! طبقة الضغط العصبي التكيفي وسياسة الوسائط ثنائية الطور (v3.1)

use dsp_modem::{OperationalMode, TransportPath};
use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum MediaError {
    #[error("AI-restored portrait images are prohibited in HostileTactical mode; use TacticalExact.")]
    VisualPortraitBlockedInTacticalMode,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AudioCodecProfile {
    Opus8To12Kbps,
    Codec2_2400Bps,
    Codec2_1600Bps,
    Codec2_1200Bps,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ImageMode {
    TacticalExactLossless,
    VisualPortraitAiRestored,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImageEnvelope {
    pub mode: ImageMode,
    pub watermark: Option<&'static str>,
    pub payload: Vec<u8>,
}

pub fn select_audio_codec(path: TransportPath) -> AudioCodecProfile {
    match path {
        TransportPath::Path1LocalBleWifi => AudioCodecProfile::Opus8To12Kbps,
        TransportPath::Path2TacticalLoRaVhf => AudioCodecProfile::Codec2_2400Bps,
        TransportPath::Path3StrategicHfNvis => AudioCodecProfile::Codec2_1200Bps,
    }
}

pub fn prepare_image_envelope(
    op_mode: OperationalMode,
    img_mode: ImageMode,
    encoded_bytes: Vec<u8>,
) -> Result<ImageEnvelope, MediaError> {
    match (op_mode, img_mode) {
        (OperationalMode::HostileTactical, ImageMode::VisualPortraitAiRestored) => {
            Err(MediaError::VisualPortraitBlockedInTacticalMode)
        }
        (_, ImageMode::TacticalExactLossless) => Ok(ImageEnvelope {
            mode: ImageMode::TacticalExactLossless,
            watermark: None,
            payload: encoded_bytes,
        }),
        (OperationalMode::ExpeditionSar, ImageMode::VisualPortraitAiRestored) => Ok(ImageEnvelope {
            mode: ImageMode::VisualPortraitAiRestored,
            watermark: Some("[AI-RESTORED PREVIEW]"),
            payload: encoded_bytes,
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_tactical_mode_blocks_ai_portrait() {
        let res = prepare_image_envelope(
            OperationalMode::HostileTactical,
            ImageMode::VisualPortraitAiRestored,
            vec![1, 2, 3],
        );
        assert_eq!(res, Err(MediaError::VisualPortraitBlockedInTacticalMode));

        let exact = prepare_image_envelope(
            OperationalMode::HostileTactical,
            ImageMode::TacticalExactLossless,
            vec![1, 2, 3],
        ).unwrap();
        assert_eq!(exact.watermark, None);
    }

    #[test]
    fn test_expedition_mode_stamps_ai_watermark() {
        let env = prepare_image_envelope(
            OperationalMode::ExpeditionSar,
            ImageMode::VisualPortraitAiRestored,
            vec![4, 5, 6],
        ).unwrap();
        assert_eq!(env.watermark, Some("[AI-RESTORED PREVIEW]"));
    }
}