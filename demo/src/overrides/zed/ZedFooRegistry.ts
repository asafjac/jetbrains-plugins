import { BaseFooRegistry } from "../../registry/FooRegistry";
import { ZedBar } from "./ZedBar";

/**
 * Overrides only the flat slot. Zed therefore appears among the targets for
 * `FooRegistry.Bar` but not for anything under `FooRegistry.qux`.
 */
export class ZedFooRegistry extends BaseFooRegistry {
    get Bar() {
        return ZedBar;
    }
}
