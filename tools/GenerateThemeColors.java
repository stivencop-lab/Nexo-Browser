import com.materialkolor.dynamiccolor.ColorSpec;
import com.materialkolor.dynamiccolor.MaterialDynamicColors;
import com.materialkolor.hct.Hct;
import com.materialkolor.scheme.DynamicScheme;
import com.materialkolor.scheme.SchemeTonalSpot;
import java.nio.file.Files;
import java.nio.file.Path;

/** Regenerate native XML tokens with the same pinned HCT scheme as BrowserColors.kt. */
class GenerateThemeColors {
    public static void main(String[] args) throws Exception {
        String[] names = {"cobalt", "violet", "cyan", "lime", "orange", "pink", "red"};
        int[] seeds = {0xff3d6bf2, 0xff7c3aed, 0xff06b6d4, 0xff22c55e, 0xfff97316, 0xffec4899, 0xffef4444};
        String[] roles = {"primary", "background", "elevated", "text", "muted", "outline", "primary_container", "on_primary_container"};
        var colors = new MaterialDynamicColors();
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            .append("<!-- HCT SchemeTonalSpot, seed colors in BrowserColors.kt; shared native dialog tokens. -->\n<resources>\n");
        for (int i = 0; i < seeds.length; i++) {
            for (boolean dark : new boolean[]{false, true}) {
                var scheme = new SchemeTonalSpot(Hct.Companion.fromInt(seeds[i]), dark, 0,
                    ColorSpec.SpecVersion.SPEC_2021, DynamicScheme.Platform.PHONE);
                int[] values = {colors.primary().getArgb(scheme), colors.surface().getArgb(scheme),
                    colors.surfaceContainerHighest().getArgb(scheme), colors.onSurface().getArgb(scheme),
                    colors.onSurfaceVariant().getArgb(scheme), colors.outlineVariant().getArgb(scheme),
                    colors.primaryContainer().getArgb(scheme), colors.onPrimaryContainer().getArgb(scheme)};
                for (int j = 0; j < roles.length; j++) {
                    xml.append(String.format("  <color name=\"nexo_%s_%s_%s\">#%08X</color>\n",
                        dark ? "dark" : "light", names[i], roles[j], values[j]));
                }
            }
        }
        for (String mode : new String[]{"light", "dark"}) {
            for (String[] alias : new String[][]{{"background", "background"}, {"surface", "elevated"}, {"text", "text"}}) {
                xml.append(String.format("  <color name=\"nexo_%s_%s\">@color/nexo_%s_cobalt_%s</color>\n", mode, alias[0], mode, alias[1]));
            }
        }
        xml.append("</resources>\n");
        if (args.length == 2 && args[0].equals("--check")) {
            if (!Files.readString(Path.of(args[1])).equals(xml.toString())) throw new IllegalStateException("Native theme tokens differ from HCT generation");
            System.out.println("Native XML tokens match the pinned HCT scheme.");
        } else if (args.length == 1) {
            Files.writeString(Path.of(args[0]), xml.toString());
        } else {
            throw new IllegalArgumentException("Pass output colors.xml, or --check colors.xml");
        }
    }
}
