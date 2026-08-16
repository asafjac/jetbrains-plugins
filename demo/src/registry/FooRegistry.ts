import { FC } from "../react";
import { Bar, BarProps } from "../components/Bar";
import { Baz, BazProps } from "../components/Baz";
import { Corge, CorgeProps } from "../components/Corge";

/**
 * A slot that hands back a group of components rather than one.
 *
 * Consumers reach into it (`FooRegistry.qux.Baz`), and an override replaces individual
 * keys through a spread, so resolving one takes two hops instead of one.
 */
export type QuxSlot = {
    Baz: FC<BazProps>;
    Corge: FC<CorgeProps>;
};

/** The neutral implementation every override starts from. */
export class BaseFooRegistry {
    /** A flat slot: the getter returns the component directly. */
    get Bar(): FC<BarProps> {
        return Bar;
    }

    /** A nested slot: the getter returns an object of components. */
    get qux(): QuxSlot {
        return {
            Baz,
            Corge,
        };
    }
}

export let FooRegistry: BaseFooRegistry = new BaseFooRegistry();

/** Called once at startup by whichever implementation is being built. */
export function setFooRegistry(registry: BaseFooRegistry) {
    FooRegistry = registry;
}
