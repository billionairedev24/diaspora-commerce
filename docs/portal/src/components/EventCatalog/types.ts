export interface JsonSchema {
  $ref?: string;
  type?: string | string[];
  format?: string;
  description?: string;
  title?: string;
  enum?: unknown[];
  const?: unknown;
  default?: unknown;
  example?: unknown;
  examples?: unknown[];
  pattern?: string;
  minimum?: number;
  maximum?: number;
  minLength?: number;
  maxLength?: number;
  minItems?: number;
  maxItems?: number;
  nullable?: boolean;
  required?: string[];
  properties?: Record<string, JsonSchema>;
  additionalProperties?: boolean | JsonSchema;
  items?: JsonSchema;
  allOf?: JsonSchema[];
  oneOf?: JsonSchema[];
  anyOf?: JsonSchema[];
}

export interface Party {
  service: string;
  operation: string;
  summary: string;
  description: string;
  channel: string;
  messageKeys: string[];
}

export interface Topic {
  address: string;
  slug: string;
  channels: { id: string; title?: string; description: string; messageKeys: string[] }[];
  producers: Party[];
  consumers: Party[];
  messageKeys: string[];
}

export interface Message {
  key: string;
  name: string;
  title: string;
  summary: string;
  description: string;
  contentType: string;
  payload: JsonSchema | null;
  headers: JsonSchema | null;
  examples: { name?: string; summary?: string; payload?: unknown; headers?: unknown }[];
  topics: string[];
}

export interface EventType {
  name: string;
  title: string;
  keys: string[];
  topics: string[];
  producers: string[];
  consumers: string[];
}

export interface EventsData {
  info: { title?: string; version?: string; description?: string; asyncapi?: string };
  servers: { id: string; host: string; protocol: string; description?: string }[];
  topics: Topic[];
  messages: Record<string, Message>;
  eventTypes: EventType[];
  schemas: Record<string, JsonSchema>;
  problems: string[];
  services: string[];
}
