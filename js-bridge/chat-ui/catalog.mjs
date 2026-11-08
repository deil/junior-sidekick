import { defineCatalog } from "@json-render/core";
import { schema, standardComponentDefinitions } from "@json-render/chat";

export const catalog = defineCatalog(schema, {
  components: standardComponentDefinitions,
});
