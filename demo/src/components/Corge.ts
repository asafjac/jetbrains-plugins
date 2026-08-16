import { FC } from "../react";

export type CorgeProps = { visible: boolean };

/**
 * Deliberately overridden by nobody. `FooRegistry.qux.Corge` should offer exactly one
 * target, which is what proves the plugin lists implementations that differ rather than
 * all of them.
 */
export const Corge: FC<CorgeProps> = (props) => ({ type: "Corge", props });
