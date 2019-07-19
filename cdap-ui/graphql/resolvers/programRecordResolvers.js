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

const programsResolver = {
  ApplicationDetail: {
    programs: async (parent, args) => {
      const programs = parent.programs;
      const type = args.type;

      if (type === null || type === undefined) {
        return programs;
      } else {
        const typePrograms = programs.filter(function(program) {
          return program.type == type;
        });

        return typePrograms;
      }
    },
  },
};

module.exports = {
  programsResolver,
};
