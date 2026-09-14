package app.kspani.ui;

import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CatalogUiEnhancerTest {
    @Test
    void recognizesEnhancedMarkerNestedInsideScrollContentWrapper() {
        VBox enhancedBody = new VBox();
        enhancedBody.setId("aokuvue-discovery-view");
        VBox wrapper = new VBox(enhancedBody);

        assertTrue(CatalogUiEnhancer.isEnhancedCatalogView(wrapper));
    }

    @Test
    void doesNotTreatLegacyCatalogAsEnhanced() {
        assertFalse(CatalogUiEnhancer.isEnhancedCatalogView(new VBox()));
    }
}
