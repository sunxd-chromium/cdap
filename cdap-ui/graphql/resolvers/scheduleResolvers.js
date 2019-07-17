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

const merge = require('lodash/merge'),
  urlHelper = require('../../server/url-helper'),
  cdapConfigurator = require('../../cdap-config.js'),
  resolversCommon = require('./resolvers-common.js');

let cdapConfig;
cdapConfigurator.getCDAPConfig().then(function(value) {
  cdapConfig = value;
});

const schedulesResolver = {
  Workflow: {
    schedules: async (parent, args, context, info) => {
      const namespace = context.namespace;
      const name = parent.app;
      const workflow = parent.name;
      const options = resolversCommon.getGETRequestOptions();
      options.headers.Authorization = context.auth;
      options['url'] = urlHelper.constructUrl(
        cdapConfig,
        `/v3/namespaces/${namespace}/apps/${name}/workflows/${workflow}/schedules`
      );

      context.workflow = workflow;

      return await resolversCommon.requestPromiseWrapper(options);
    },
  },
};

const nextRuntimesResolver = {
  ScheduleDetail: {
    nextRuntimes: async (parent, args, context, info) => {
      const namespace = context.namespace;
      const name = parent.application;
      const workflow = context.workflow;
      const options = resolversCommon.getGETRequestOptions();
      options.headers.Authorization = context.auth;
      options['url'] = urlHelper.constructUrl(
        cdapConfig,
        `/v3/namespaces/${namespace}/apps/${name}/workflows/${workflow}/nextruntime`
      );

      const times = await resolversCommon.requestPromiseWrapper(options);

      return times.map((time) => {
        return time.time;
      });
    },
  },
};

const scheduleResolvers = merge(schedulesResolver, nextRuntimesResolver);

module.exports = {
  scheduleResolvers,
};
