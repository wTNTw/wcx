package me.yun.silk;

import com.Johnny.wcx.utils.AudioUtils;

import java.io.File;

/** Compatibility facade backed by WCX's on-demand {@code libwekit_media.so}. */
public class SilkCodec {

    /** 时长限制：60秒 */
    public static final long MAX_DURATION_MS = 60_000L;

    public int getFileType(String filePath) {
        try {
            return AudioUtils.INSTANCE.getFileType(filePath);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public int mp3ToSilk(String mp3Path, String silkPath, int hz) {
        return anyToSilk(mp3Path, silkPath, hz);
    }

    public int wavToSilk(String wavPath, String silkPath, int hz) {
        return anyToSilk(wavPath, silkPath, hz);
    }

    public int flacToSilk(String flacPath, String silkPath, int hz) {
        return anyToSilk(flacPath, silkPath, hz);
    }

    public int oggToSilk(String oggPath, String silkPath, int hz) {
        return anyToSilk(oggPath, silkPath, hz);
    }

    public int pcmToSilk(
            String pcmPath,
            String silkPath,
            int hz,
            int pcmHz,
            int channels
    ) {
        try {
            return AudioUtils.INSTANCE.pcmToSilk(pcmPath, silkPath, hz, pcmHz, channels) ? 0 : -701;
        } catch (Throwable ignored) {
            return -701;
        }
    }

    public int autoToSilk(String audioPath, String silkPath, int hz) {
        try {
            return AudioUtils.INSTANCE.anyToSilkAtRate(audioPath, silkPath, hz) ? 0 : -2;
        } catch (Throwable ignored) {
            return -2;
        }
    }

    public int silkToMp3(String silkPath, String mp3Path, int hz) {
        File pcm = new File(mp3Path + ".wcx-" + Thread.currentThread().getId() + ".pcm");
        try {
            if (!AudioUtils.INSTANCE.silkToPcmAtRate(silkPath, pcm.getAbsolutePath(), hz)) {
                return -201;
            }
            return AudioUtils.INSTANCE.pcmToMp3AtRate(pcm.getAbsolutePath(), mp3Path, hz) ? 0 : -202;
        } catch (Throwable ignored) {
            return -201;
        } finally {
            if (!pcm.delete() && pcm.exists()) pcm.deleteOnExit();
        }
    }

    public int silkToPcm(String silkPath, String pcmPath, int hz) {
        try {
            return AudioUtils.INSTANCE.silkToPcmAtRate(silkPath, pcmPath, hz) ? 0 : -201;
        } catch (Throwable ignored) {
            return -201;
        }
    }

    public int mp3ToPcm(String mp3Path, String pcmPath) {
        return anyToPcm(mp3Path, pcmPath, -301);
    }

    public int wavToPcm(String wavPath, String pcmPath) {
        return anyToPcm(wavPath, pcmPath, -501);
    }

    public int flacToPcm(String flacPath, String pcmPath) {
        return anyToPcm(flacPath, pcmPath, -601);
    }

    public int oggToPcm(String oggPath, String pcmPath) {
        return anyToPcm(oggPath, pcmPath, -401);
    }

    public int autoToPcm(String audioPath, String pcmPath) {
        return anyToPcm(audioPath, pcmPath, -3);
    }

    public long getDuration(String filePath) {
        try {
            return AudioUtils.INSTANCE.getDurationMs(filePath);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public long getDurationLimited(String filePath) {
        long duration = getDuration(filePath);
        return duration > MAX_DURATION_MS ? MAX_DURATION_MS : duration;
    }

    private int anyToSilk(String inputPath, String silkPath, int hz) {
        try {
            return AudioUtils.INSTANCE.anyToSilkAtRate(inputPath, silkPath, hz) ? 0 : -2;
        } catch (Throwable ignored) {
            return -2;
        }
    }

    private int anyToPcm(String inputPath, String pcmPath, int errorCode) {
        try {
            return AudioUtils.INSTANCE.anyToPcm(inputPath, pcmPath) > 0 ? 0 : errorCode;
        } catch (Throwable ignored) {
            return errorCode;
        }
    }
}
