package com.example.pitchchanger.Controller;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.MultichannelToMono;
import be.tarsos.dsp.WaveformSimilarityBasedOverlapAdd;
import be.tarsos.dsp.WaveformSimilarityBasedOverlapAdd.Parameters;
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory;
import be.tarsos.dsp.resample.RateTransposer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/audio")
public class AudioController {

    @Value("${ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    @Value("${ffmpeg.timeout:30}")
    private int ffmpegTimeoutSeconds;

    @PostMapping("/pitch")
    public ResponseEntity<byte[]> autoPitchChange(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value="targetNote",defaultValue = "") String targetNote,
            @RequestParam(value="shift",defaultValue = "0") int Pshift
    ) throws Exception {
        boolean keepTempo=true;
        String originalFilename = file.getOriginalFilename();
        System.out.println("=== PROCESSING FILE: " + originalFilename + " ===");

        // Create temporary files
        String timestamp = String.valueOf(System.currentTimeMillis());
        File inputFile = File.createTempFile("pitch_input_" + timestamp + "_", getFileExtension(originalFilename));
        File wavFile = File.createTempFile("pitch_wav_" + timestamp + "_", ".wav");
        File outputFile = File.createTempFile("pitch_output_" + timestamp + "_", ".wav");

        inputFile.deleteOnExit();
        wavFile.deleteOnExit();
        outputFile.deleteOnExit();

        try {
            // Step 1: Save uploaded file
            file.transferTo(inputFile);
            System.out.println("Uploaded file saved: " + inputFile.length() + " bytes");

            // Step 2: Convert to WAV using FFmpeg
            if (!convertToWavWithFFmpeg(inputFile, wavFile)) {
                throw new RuntimeException("Failed to convert audio to WAV format");
            }

            // Step 3: Detect pitch
            float detectedPitch = detectPitchFromWav(wavFile);
            if (detectedPitch <= 0) {
                detectedPitch = 220.0f; // A3 fallback
                System.out.println("Using fallback pitch: " + detectedPitch + " Hz");
            }

            // Step 4: Calculate pitch shift in semitones
            int semitoneSteps=Pshift;
            System.out.println("targetNote:" + targetNote);
            if(!targetNote.equals("")) {
                int sourceMidi = hzToMidi(detectedPitch);
                int targetMidi = noteToMidi(targetNote);
                semitoneSteps = targetMidi - sourceMidi;
            }
            if (Math.abs(semitoneSteps) > 24) {
                throw new IllegalArgumentException("Pitch shift too extreme: " + semitoneSteps + " semitones");
            }

            System.out.println("Pitch shift: " + semitoneSteps + " semitones");

            // Step 5: Process with extracted TarsosDSP code
            processWithExtractedCode(wavFile, outputFile, semitoneSteps, keepTempo);

            // Step 6: Return result
            byte[] result = Files.readAllBytes(outputFile.toPath());
            System.out.println("Processing complete: " + result.length + " bytes");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"pitched_" + targetNote + ".wav\"")
                    .contentType(MediaType.parseMediaType("audio/wav"))
                    .body(result);

        } finally {
            cleanup(inputFile, wavFile, outputFile);
        }
    }

    /**
     * Process audio using the extracted TarsosDSP pitch shifting code
     */
    private void processWithExtractedCode(File inputFile, File outputFile, int semitoneSteps, boolean keepTempo)
            throws Exception {

        System.out.println("Processing with extracted TarsosDSP code...");
        System.out.println("Semitone steps: " + semitoneSteps);
        System.out.println("Keep original tempo: " + keepTempo);

        try {
            // Convert semitones to cents (TarsosDSP uses cents)
            double cents = semitoneSteps * 100.0;

            // Get audio format
            AudioFormat format = AudioSystem.getAudioFileFormat(inputFile).getFormat();
            double sampleRate = format.getSampleRate();

            System.out.println("Audio format: " + format);
            System.out.println("Sample rate: " + sampleRate);
            System.out.println("Channels: " + format.getChannels());

            // Convert cents to pitch factor using the extracted formula
            double pitchFactor = centToFactor(cents);
            System.out.println("Pitch factor: " + pitchFactor);

            // Create WSOLA processor (the key to quality pitch shifting)
            WaveformSimilarityBasedOverlapAdd wsola;
            if (keepTempo) {
                // Use pitch factor for time stretching to maintain tempo
                wsola = new WaveformSimilarityBasedOverlapAdd(
                        Parameters.musicDefaults(pitchFactor, sampleRate)
                );
            } else {
                // No time stretching - pitch and tempo both change
                wsola = new WaveformSimilarityBasedOverlapAdd(
                        Parameters.musicDefaults(1.0, sampleRate)
                );
            }
            System.out.println("bufferSize="+wsola.getInputBufferSize()+",overlap="+wsola.getOverlap());
            // Create rate transposer
            RateTransposer rateTransposer = new RateTransposer(pitchFactor);

            // Create output writer
            TarsosDSPFileWriter writer = new TarsosDSPFileWriter(outputFile, format);

            // Create audio dispatcher
            AudioDispatcher dispatcher;
            if (format.getChannels() != 1) {
                // Handle multichannel audio by converting to mono
                System.out.println("Converting multichannel to mono");
                dispatcher = AudioDispatcherFactory.fromFile(
                        inputFile,
                        wsola.getInputBufferSize() * format.getChannels(),
                        wsola.getOverlap() * format.getChannels()
                );
                dispatcher.addAudioProcessor(new MultichannelToMono(format.getChannels(), true));
            } else {
                // Mono audio
                dispatcher = AudioDispatcherFactory.fromFile(
                        inputFile,
                        wsola.getInputBufferSize(),
                        wsola.getOverlap()
                );
            }

            // Set up processing chain (ORDER MATTERS!)
            wsola.setDispatcher(dispatcher);
            dispatcher.addAudioProcessor(wsola);

            if (keepTempo) {
                // Add rate transposer to adjust playback speed
                dispatcher.addAudioProcessor(rateTransposer);
            }

            dispatcher.addAudioProcessor(writer);

            // Run processing
            System.out.println("Starting audio processing...");
            dispatcher.run();

            System.out.println("TarsosDSP processing completed successfully");
            System.out.println("Output file size: " + outputFile.length() + " bytes");

        } catch (Exception e) {
            System.out.println("Error in extracted TarsosDSP processing: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * Convert cents to pitch shift factor (extracted from TarsosDSP example)
     */
    private static double centToFactor(double cents) {
        // Original formula from TarsosDSP example
        return 1.0 / Math.pow(Math.E, cents * Math.log(2) / 1200 / Math.log(Math.E));

        // Alternative simplified formula (mathematically equivalent):
        // return Math.pow(2.0, cents / 1200.0);
    }

    /**
     * Custom file writer for TarsosDSP output
     */
    private static class TarsosDSPFileWriter implements be.tarsos.dsp.AudioProcessor {
        private final File outputFile;
        private final AudioFormat format;
        private final ByteArrayOutputStream buffer;

        public TarsosDSPFileWriter(File outputFile, AudioFormat inputFormat) {
            this.outputFile = outputFile;
            // Create mono format for output
            this.format = new AudioFormat(
                    inputFormat.getSampleRate(),
                    16, 1, true, false
            );
            this.buffer = new ByteArrayOutputStream();
        }

        @Override
        public boolean process(be.tarsos.dsp.AudioEvent audioEvent) {
            float[] samples = audioEvent.getFloatBuffer();

            for (float sample : samples) {
                // Clamp sample to prevent clipping
                sample = Math.max(-1.0f, Math.min(1.0f, sample));

                // Convert to 16-bit PCM
                short pcmSample = (short) (sample * 32767);

                // Write as little-endian bytes
                buffer.write(pcmSample & 0xFF);
                buffer.write((pcmSample >> 8) & 0xFF);
            }

            return true;
        }

        @Override
        public void processingFinished() {
            try {
                System.out.println("Writing processed audio to file...");

                byte[] audioData = buffer.toByteArray();
                System.out.println("Generated " + audioData.length + " bytes of audio data");

                if (audioData.length > 0) {
                    ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
                    AudioInputStream ais = new AudioInputStream(bais, format, audioData.length / format.getFrameSize());

                    AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outputFile);
                    ais.close();

                    System.out.println("Audio file written: " + outputFile.length() + " bytes");
                } else {
                    System.out.println("Warning: No audio data to write");
                }

                buffer.close();

            } catch (Exception e) {
                System.out.println("Error writing audio file: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // FFmpeg conversion method (unchanged)
    private boolean convertToWavWithFFmpeg(File inputFile, File outputFile) {
        try {
            List<String> command = Arrays.asList(
                    ffmpegPath,
                    "-i", inputFile.getAbsolutePath(),
                    "-ar", "44100",
                    "-ac", "1",
                    "-sample_fmt", "s16",
                    "-f", "wav",
                    "-y",
                    outputFile.getAbsolutePath()
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();

            boolean finished = process.waitFor(ffmpegTimeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0 && outputFile.exists() && outputFile.length() > 0;

        } catch (Exception e) {
            System.out.println("FFmpeg conversion error: " + e.getMessage());
            return false;
        }
    }

    // Utility methods (unchanged)
    private float detectPitchFromWav(File wavFile) {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(wavFile)) {
            AudioFormat format = ais.getFormat();

            int sampleSize = 8192;
            byte[] buffer = new byte[sampleSize * format.getFrameSize()];
            int bytesRead = ais.read(buffer);

            if (bytesRead > 0) {
                float[] samples = convertToMonoFloat(buffer, bytesRead, format);
                return detectPitchAutocorrelation(samples, format.getSampleRate());
            }

        } catch (Exception e) {
            System.out.println("Pitch detection error: " + e.getMessage());
        }
        return -1;
    }

    private float[] convertToMonoFloat(byte[] buffer, int bytesRead, AudioFormat format) {
        int channels = format.getChannels();
        int bytesPerFrame = format.getFrameSize();
        int frames = bytesRead / bytesPerFrame;
        float[] samples = new float[frames];

        for (int frame = 0; frame < frames; frame++) {
            float sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                int offset = frame * bytesPerFrame + channel * 2;
                if (offset + 1 < bytesRead) {
                    short sample = (short) ((buffer[offset + 1] << 8) | (buffer[offset] & 0xFF));
                    sum += sample;
                }
            }
            samples[frame] = (sum / channels) / 32768.0f;
        }
        return samples;
    }

    private float detectPitchAutocorrelation(float[] buffer, float sampleRate) {
        int minPeriod = (int) (sampleRate / 800);
        int maxPeriod = (int) (sampleRate / 80);

        if (maxPeriod >= buffer.length / 2) {
            maxPeriod = buffer.length / 2 - 1;
        }

        float maxCorrelation = 0;
        int bestPeriod = 0;

        for (int period = minPeriod; period < maxPeriod; period++) {
            float correlation = 0;
            int samples = buffer.length - period;

            for (int i = 0; i < samples; i++) {
                correlation += buffer[i] * buffer[i + period];
            }

            correlation /= samples;

            if (correlation > maxCorrelation) {
                maxCorrelation = correlation;
                bestPeriod = period;
            }
        }

        return bestPeriod > 0 ? sampleRate / bestPeriod : -1;
    }

    private void cleanup(File... files) {
        for (File file : files) {
            if (file != null && file.exists()) {
                boolean deleted = file.delete();
                System.out.println("Cleaned up " + file.getName() + ": " + deleted);
            }
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return ".tmp";
        }
        int lastDot = filename.lastIndexOf('.');
        return lastDot >= 0 ? filename.substring(lastDot) : ".tmp";
    }

    private int hzToMidi(float frequency) {
        return (int) Math.round(69 + 12 * Math.log(frequency / 440.0) / Math.log(2));
    }

    private int noteToMidi(String note) {
        Map<String, Integer> noteMap = new HashMap<>();
        noteMap.put("C", 0); noteMap.put("C#", 1); noteMap.put("DB", 1);
        noteMap.put("D", 2); noteMap.put("D#", 3); noteMap.put("EB", 3);
        noteMap.put("E", 4); noteMap.put("F", 5);
        noteMap.put("F#", 6); noteMap.put("GB", 6);
        noteMap.put("G", 7); noteMap.put("G#", 8); noteMap.put("AB", 8);
        noteMap.put("A", 9); noteMap.put("A#", 10); noteMap.put("BB", 10);
        noteMap.put("B", 11);

        String noteName = note.replaceAll("[0-9-]", "").toUpperCase();
        String octaveStr = note.replaceAll("[^0-9-]", "");

        if (octaveStr.isEmpty()) {
            throw new IllegalArgumentException("Invalid note format: " + note);
        }

        int octave = Integer.parseInt(octaveStr);
        if (!noteMap.containsKey(noteName)) {
            throw new IllegalArgumentException("Invalid note: " + noteName);
        }

        return (octave + 1) * 12 + noteMap.get(noteName);
    }
}