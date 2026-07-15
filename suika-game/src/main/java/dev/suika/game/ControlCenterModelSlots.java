package dev.suika.game;

/**
 * Model-slot status copy for Control Center — uses {@link ModelSlots#hasOnnx} /
 * {@link ModelSlots#hasPlayablePolicy} so ONNX-only slots are not shown as empty.
 */
public final class ControlCenterModelSlots {

    private ControlCenterModelSlots() {}

    public record Status(boolean present, boolean onnx, boolean playablePolicy,
                         String detailLine, String badge) {}

    public static Status status(String techniqueId, int slot, ModelSlots.SlotInfo info) {
        boolean onnx = ModelSlots.hasOnnx(techniqueId, slot);
        boolean playable = ModelSlots.hasPlayablePolicy(techniqueId, slot);
        boolean present = info.present() || onnx || playable;
        String badge;
        if (onnx && ModelSlots.hasWeights(techniqueId, slot)) badge = "weights+ONNX";
        else if (onnx) badge = "ONNX";
        else if (ModelSlots.hasWeights(techniqueId, slot)) badge = "weights";
        else if (info.present()) badge = "config";
        else badge = "empty";

        String detail;
        if (!present) {
            detail = "empty";
        } else if (info.present()) {
            detail = String.format("saved %tR  ·  score %.0f  ·  %s",
                    new java.util.Date(info.savedAtMillis()), info.score(), badge);
        } else {
            detail = "ONNX policy present (no manifest score yet) · " + badge;
        }
        return new Status(present, onnx, playable, detail, badge);
    }

    /** Load button enabled when anything playable or config-present exists. */
    public static boolean canLoad(Status st) {
        return st.present;
    }
}
