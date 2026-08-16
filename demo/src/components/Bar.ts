import { FC } from "../react";

export type BarProps = { label: string };

/** The neutral Bar. `FooRegistry.Bar` resolves here unless something overrides it. */
export const Bar: FC<BarProps> = (props) => ({ type: "Bar", props });
