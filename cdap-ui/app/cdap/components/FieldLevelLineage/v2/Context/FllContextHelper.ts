/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
*/

import { TIME_OPTIONS } from 'components/FieldLevelLineage/store/Store';
import { TIME_OPTIONS_MAP } from 'components/FieldLevelLineage/store/ActionCreator';
import { parseQueryString } from 'services/helpers';
import { MyMetadataApi } from 'api/metadata';

// types for backend response
interface IFllEntity {
  entityId?: IEntityId;
  relations?: IRelation[];
}

interface IEntityId {
  namespace?: string;
  dataset?: string;
  entity?: string;
}

export interface IRelation {
  source?: string;
  destination?: string;
}

// These types are used by the frontend
export interface IField {
  id: string;
  type: string;
  name: string;
  dataset: string;
  namespace: string;
}

export interface ILink {
  source: IField;
  destination: IField;
}

export interface ITableFields {
  [tablename: string]: IField[];
}

/** Parses an incoming or outgoing entity object from backend response
 * to get array of edges and an object with fields keyed by dataset.
 * namespace and target are the target namespace and dataset name
 */

export function parseRelations(
  namespace: string,
  target: string,
  ents: IFllEntity[],
  isCause: boolean = true
) {
  const tables: ITableFields = {};
  const relLinks = [];
  ents.map((ent) => {
    // Assumes that all tableNames are unique within a namespace

    const type = isCause ? 'cause' : 'impact';
    const tableId = getTableId(ent.entityId.dataset, ent.entityId.namespace, type);
    // tables keeps track of fields for each incoming or outgoing dataset.
    tables[tableId] = [];
    // fieldIds keeps track of fields, since a single field can have multiple connections
    const fieldIds = new Map();
    ent.relations.map((rel) => {
      /** backend response assumes connection goes from left to right
       * i.e. an incoming connection's destination = target field,
       * and outgoing connection's source = target field.
       */

      const fieldName = isCause ? rel.source : rel.destination;
      let id = fieldIds.get(fieldName);
      const field: IField = {
        id,
        type: isCause ? 'cause' : 'impact',
        name: fieldName,
        dataset: ent.entityId.dataset,
        namespace: ent.entityId.namespace,
      };
      if (!fieldIds.has(fieldName)) {
        id = getFieldId(fieldName, ent.entityId.dataset, ent.entityId.namespace, type);
        field.id = id;
        fieldIds.set(fieldName, id);
        tables[tableId].push(field);
      }

      const targetName = isCause ? rel.destination : rel.source;
      const targetField: IField = {
        id: getFieldId(targetName, target, namespace, 'target'),
        type: 'target',
        dataset: target,
        namespace,
        name: isCause ? rel.destination : rel.source,
      };
      const link: ILink = {
        source: isCause ? field : targetField,
        destination: isCause ? targetField : field,
      };
      relLinks.push(link);
    });
  });
  return { tables, relLinks };
}

export function getFieldsAndLinks(d) {
  const incoming = parseRelations(d.entityId.namespace, d.entityId.dataset, d.incoming);
  const outgoing = parseRelations(d.entityId.namespace, d.entityId.dataset, d.outgoing, false);
  const causeTables = incoming.tables;
  const impactTables = outgoing.tables;
  const links = incoming.relLinks.concat(outgoing.relLinks);
  return { causeTables, impactTables, links };
}

export function makeTargetFields({ namespace, dataset }: IEntityId, fields: string[]) {
  const targetFields = fields.map((fieldname) => {
    const id = `target_ns-${namespace}_ds-${dataset}_fd-${fieldname}`;
    const field: IField = {
      id,
      type: 'target',
      name: fieldname,
      dataset,
      namespace,
    };
    return field;
  });
  return targetFields;
}

export function getFieldId(fieldname, dataset, namespace, type) {
  return `${type}_ns-${namespace}_ds-${dataset}_fd-${fieldname}`;
}

export function getTableId(dataset, namespace, type) {
  return `${type}_ns-${namespace}_ds-${dataset}`;
}

export function getTimeRange() {
  const queryString = parseQueryString();
  const selection = queryString ? queryString.time : TIME_OPTIONS[1]; // default is last 7 days

  if (selection === TIME_OPTIONS[0]) {
    return {
      start: selection.start || 'now-7d',
      end: selection.end || 'now',
    };
  }
  return TIME_OPTIONS_MAP[selection];
}

export function fetchFieldLineage(context, namespace, dataset, qParams, timeRange) {
  let fieldname;
  let activeField;

  if (!qParams) {
    fieldname = null;
    activeField = null;
  } else {
    fieldname = qParams.field;
    activeField = getFieldId(fieldname, dataset, namespace, 'target');
  }

  const start = timeRange.start;
  const end = timeRange.end;

  const params = {
    namespace,
    entityId: dataset,
    direction: 'both',
    start,
    end,
  };

  MyMetadataApi.getAllFieldLineage(params).subscribe((res) => {
    const parsedRes = getFieldsAndLinks(res);
    const targetInfo = {
      target: res.entityId.dataset,
      targetFields: makeTargetFields(res.entityId, res.fields),
      links: parsedRes.links,
      causeSets: parsedRes.causeTables,
      impactSets: parsedRes.impactTables,
      activeField,
    };

    context.setState(targetInfo);
  });
}
