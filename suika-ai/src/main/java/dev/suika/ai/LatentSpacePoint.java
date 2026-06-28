package dev.suika.ai;

/**
 * A single point in the board-state latent space for UMAP/t-SNE visualisation.
 * Collected by the VAE encoder and displayed in the Dashboard latent-space explorer.
 */
public record LatentSpacePoint(
        double[] coordinates,   // 2-D or 3-D projection after UMAP
        long     score,
        int      stepIndex,
        long     episodeSeed
) {}
