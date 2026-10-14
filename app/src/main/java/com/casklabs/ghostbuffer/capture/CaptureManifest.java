package com.casklabs.ghostbuffer.capture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Manifest containing session metadata and per-frame timing details.
 */
public class CaptureManifest {
    public static class FrameInfo {
        public final String filename;
        public final long sequence;
        public final int sizeBytes;
        public final long offsetFromTriggerMs;
        public final long readDurationMs;

        public FrameInfo(String filename, long sequence, int sizeBytes, long offsetFromTriggerMs, long readDurationMs) {
            this.filename = filename;
            this.sequence = sequence;
            this.sizeBytes = sizeBytes;
            this.offsetFromTriggerMs = offsetFromTriggerMs;
            this.readDurationMs = readDurationMs;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("filename", filename);
            obj.put("sequence", sequence);
            obj.put("size_bytes", sizeBytes);
            obj.put("offset_from_trigger_ms", offsetFromTriggerMs);
            obj.put("read_duration_ms", readDurationMs);
            return obj;
        }

        public static FrameInfo fromJson(JSONObject obj) throws JSONException {
            return new FrameInfo(
                obj.getString("filename"),
                obj.getLong("sequence"),
                obj.getInt("size_bytes"),
                obj.getLong("offset_from_trigger_ms"),
                obj.getLong("read_duration_ms")
            );
        }
    }

    private int version = 1;
    private String appVersion = "1.0.0";
    private String cameraModel = "ILCE-5000";
    private String sessionId;
    private String createdAt;
    private long triggerTimeMs;
    private long bufferDurationMs;
    private long actualDurationMs;
    private double targetFps;
    private double measuredFps;
    private int frameWidth = 640;
    private int frameHeight = 480;
    private final List<FrameInfo> frames = new ArrayList<FrameInfo>();

    public CaptureManifest() {}

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }

    public String getCameraModel() { return cameraModel; }
    public void setCameraModel(String cameraModel) { this.cameraModel = cameraModel; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public long getTriggerTimeMs() { return triggerTimeMs; }
    public void setTriggerTimeMs(long triggerTimeMs) { this.triggerTimeMs = triggerTimeMs; }

    public long getBufferDurationMs() { return bufferDurationMs; }
    public void setBufferDurationMs(long bufferDurationMs) { this.bufferDurationMs = bufferDurationMs; }

    public long getActualDurationMs() { return actualDurationMs; }
    public void setActualDurationMs(long actualDurationMs) { this.actualDurationMs = actualDurationMs; }

    public double getTargetFps() { return targetFps; }
    public void setTargetFps(double targetFps) { this.targetFps = targetFps; }

    public double getMeasuredFps() { return measuredFps; }
    public void setMeasuredFps(double measuredFps) { this.measuredFps = measuredFps; }

    public int getFrameWidth() { return frameWidth; }
    public void setFrameWidth(int frameWidth) { this.frameWidth = frameWidth; }

    public int getFrameHeight() { return frameHeight; }
    public void setFrameHeight(int frameHeight) { this.frameHeight = frameHeight; }

    public int getFrameCount() { return frames.size(); }

    public List<FrameInfo> getFrames() { return frames; }
    public void addFrame(FrameInfo frame) { frames.add(frame); }

    public String toJsonString() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", version);
        root.put("app_version", appVersion);
        root.put("camera_model", cameraModel);
        root.put("session_id", sessionId);
        root.put("created_at", createdAt);
        root.put("trigger_time_ms", triggerTimeMs);
        root.put("buffer_duration_ms", bufferDurationMs);
        root.put("actual_duration_ms", actualDurationMs);
        root.put("target_fps", targetFps);
        root.put("measured_fps", measuredFps);
        root.put("frame_width", frameWidth);
        root.put("frame_height", frameHeight);
        root.put("frame_count", frames.size());

        JSONArray framesArray = new JSONArray();
        for (FrameInfo frame : frames) {
            framesArray.put(frame.toJson());
        }
        root.put("frames", framesArray);

        return root.toString(2);
    }

    public static CaptureManifest fromJsonString(String jsonString) throws JSONException {
        JSONObject root = new JSONObject(jsonString);
        CaptureManifest manifest = new CaptureManifest();
        manifest.setVersion(root.getInt("version"));
        manifest.setAppVersion(root.optString("app_version", "1.0.0"));
        manifest.setCameraModel(root.optString("camera_model", "Unknown"));
        manifest.setSessionId(root.getString("session_id"));
        manifest.setCreatedAt(root.optString("created_at", ""));
        manifest.setTriggerTimeMs(root.getLong("trigger_time_ms"));
        manifest.setBufferDurationMs(root.getLong("buffer_duration_ms"));
        manifest.setActualDurationMs(root.getLong("actual_duration_ms"));
        manifest.setTargetFps(root.optDouble("target_fps", 10.0));
        manifest.setMeasuredFps(root.optDouble("measured_fps", 0.0));
        manifest.setFrameWidth(root.optInt("frame_width", 640));
        manifest.setFrameHeight(root.optInt("frame_height", 480));

        JSONArray framesArray = root.getJSONArray("frames");
        for (int i = 0; i < framesArray.length(); i++) {
            manifest.addFrame(FrameInfo.fromJson(framesArray.getJSONObject(i)));
        }
        return manifest;
    }
}
