import { Button } from "../ui/button";
import { CardDescription, CardTitle } from "../ui/card";
import type { LocalStackModel } from "../../lib/recommendedLocalStack";

interface Props {
  label: string;
  recommended: string | null;
  current: string | null;
  available: LocalStackModel[];
  onSelect: (modelId: string) => void;
  renderMeta?: (modelId: string) => LocalStackModel | null;
  renderChip?: (model: LocalStackModel) => React.ReactNode;
}

export function StackChoice({ label, recommended, current, available, onSelect, renderMeta, renderChip }: Props) {
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <CardTitle className="text-base">{label}</CardTitle>
        {recommended && (
          <span className="text-xs text-muted-foreground">
            {recommended === current ? "Recommended" : `Recommended: ${recommended}`}
          </span>
        )}
      </div>
      <div className="flex flex-wrap gap-2">
        {available.map((model) => {
          const selected = current === model.modelId;
          return (
            <Button
              key={model.modelId}
              type="button"
              variant={selected ? "default" : "outline"}
              size="sm"
              onClick={() => onSelect(model.modelId)}
            >
              {model.displayName}
              {renderChip ? renderChip(model) : null}
            </Button>
          );
        })}
      </div>
      {current && (
        <CardDescription className="text-xs">
          {renderMeta?.(current) ? renderMeta(current).description ?? "" : ""}
        </CardDescription>
      )}
    </div>
  );
}
