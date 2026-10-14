import { ReactNode } from "react";

interface Props {
  title: string;
  description?: string;
  titleLines?: string[];
}

export function StepHeader({ title, description, titleLines }: Props) {
  return (
    <div className="flex flex-col gap-2">
      <h1 className="text-xl font-semibold text-default-foreground">
        {titleLines ? titleLines.map((line, index) => (
          <span key={index} className="block">{line}</span>
        )) : title}
      </h1>
      {description && <p className="text-sm text-muted-foreground">{description}</p>}
    </div>
  );
}
