import { BaseFooRegistry } from "../../registry/FooRegistry";
import { AcmeBar } from "./AcmeBar";
import { AcmeBaz } from "./AcmeBaz";

/** Overrides both kinds of slot: the flat one outright, one key of the nested one. */
export class AcmeFooRegistry extends BaseFooRegistry {
    get Bar() {
        return AcmeBar;
    }

    get qux() {
        // Spread first, override second: Corge keeps the neutral implementation, so Acme
        // should not appear among the targets for FooRegistry.qux.Corge.
        return {
            ...super.qux,
            Baz: AcmeBaz,
        };
    }
}
