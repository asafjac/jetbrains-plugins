/**
 * Just enough of React's surface for the fixture to read like real code.
 *
 * Declared locally rather than depended on: the plugin resolves through the PSI, which
 * needs no node_modules, and a fixture that requires an install is a fixture nobody runs.
 */
export type JsxNode = { type: unknown; props: Record<string, unknown> };

export type FC<P> = (props: P) => JsxNode;

declare global {
    namespace JSX {
        type Element = JsxNode;
        interface ElementChildrenAttribute {
            children: unknown;
        }
        interface IntrinsicElements {
            [tag: string]: Record<string, unknown>;
        }
    }
}
