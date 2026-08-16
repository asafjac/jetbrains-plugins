import { setFooRegistry } from "./registry/FooRegistry";
import { AcmeFooRegistry } from "./overrides/acme/AcmeFooRegistry";

/**
 * The startup swap. This is the whole reason navigation is hard: which component
 * `FooRegistry.Bar` renders is decided here, at runtime, not at the call site.
 */
setFooRegistry(new AcmeFooRegistry());
