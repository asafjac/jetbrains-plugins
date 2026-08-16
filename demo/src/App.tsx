import { FooRegistry } from "./registry/FooRegistry";

/**
 * Try the plugin here. Ctrl+Click (Cmd+Click) any segment of a registry tag below.
 *
 * Expected targets, and why:
 *
 *   FooRegistry.Bar
 *     FooRegistry -> BaseFooRegistry, AcmeFooRegistry, ZedFooRegistry   (3 classes)
 *     Bar         -> Bar, AcmeBar, ZedBar                               (3 components)
 *
 *   FooRegistry.qux.Baz
 *     qux         -> get qux() in BaseFooRegistry and AcmeFooRegistry   (2 getters, not 3:
 *                    Zed never overrides the nested slot)
 *     Baz         -> Baz, AcmeBaz                                       (2 components)
 *
 *   FooRegistry.qux.Corge
 *     Corge       -> Corge only                                         (1 component)
 *                    Acme spreads ...super.qux without redefining Corge, so it has no
 *                    answer of its own and is left out.
 *
 * Without the plugin, reaching Acme's component from here takes four hops:
 *
 *   1. Ctrl+B on Baz         -> Baz in the QuxSlot type. A type, not a component.
 *   2. open BaseFooRegistry  -> get qux(), returning the neutral Baz
 *   3. Ctrl+Alt+B on qux     -> list of overriding getters; pick AcmeFooRegistry
 *   4. Ctrl+B on AcmeBaz     -> finally, the component
 *
 * With the plugin that is one Ctrl+Click, and it shows every implementation at once
 * rather than one per trip.
 *
 * The counts are the interesting part. A tool that simply listed every subclass would
 * answer 3 every time; these differ because each reports only what actually differs.
 */
export function App() {
    return (
        <div>
            <FooRegistry.Bar label="flat slot" />
            <FooRegistry.qux.Baz amount={42} />
            <FooRegistry.qux.Corge visible={true} />
        </div>
    );
}

/**
 * The same accesses as plain expressions rather than JSX tags. The plugin resolves both,
 * through quite different code paths, so both are worth having in the fixture.
 */
export const flatSlot = FooRegistry.Bar;
export const nestedSlot = FooRegistry.qux.Baz;
