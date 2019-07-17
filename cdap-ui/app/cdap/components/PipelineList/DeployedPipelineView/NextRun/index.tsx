/*
 * Copyright © 2018 Cask Data, Inc.
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

import * as React from 'react';
import IconSVG from 'components/IconSVG';
import { Observable } from 'rxjs/Observable';
import Duration from 'components/Duration';
import { GLOBALS } from 'services/global-constants';
import { IApplicationRecord } from 'components/PipelineList/DeployedPipelineView/types';
import { objectQuery } from 'services/helpers';
import { getProgram } from 'components/PipelineList/DeployedPipelineView/graphqlHelper';

interface IProps {
  pipeline: IApplicationRecord;
}

interface IState {
  loading: boolean;
  nextRun: null | number;
}

export default class NextRun extends React.PureComponent<IProps, IState> {
  private interval = null;

  public state = {
    loading: true,
    nextRun: null,
  };

  public componentDidMount() {
    if (this.props.pipeline.artifact.name === GLOBALS.etlDataStreams) {
      return;
    }

    // Interval only runs after the delay, so have to
    // initially call the function first
    this.getNextRun();
    this.interval = Observable.interval(30 * 1000).subscribe(this.getNextRun.bind(this));
  }

  public componentWillUnmount() {
    if (this.interval && this.interval.unsubscribe) {
      this.interval.unsubscribe();
    }
  }

  private getNextRun() {
    const latestSchedule = this.getLatestSchedule(this.props.pipeline);
    const nextRuntime = latestSchedule.nextRuntimes[0];

    this.setState({
      loading: false,
      nextRun: nextRuntime ? parseInt(nextRuntime, 10) : null,
    });
  }

  private getLatestSchedule(pipeline) {
    const program = getProgram(pipeline);
    const schedules = objectQuery(program, 'schedules');

    if (schedules === null || schedules === undefined || schedules.length === 0) {
      return {};
    }

    return schedules[0];
  }

  private renderContent() {
    if (
      this.props.pipeline.artifact.name === GLOBALS.etlDataStreams ||
      (!this.state.loading && !this.state.nextRun)
    ) {
      return <span>--</span>;
    }

    if (this.state.loading) {
      // we have fa-lg here because we use tags on the same table row which is using larger loading icon
      // If both happen to load in UI it will this discrepancy (md vs lg)
      return (
        <span className="fa fa-spin fa-lg">
          <IconSVG name="icon-spinner" />
        </span>
      );
    }

    return <Duration targetTime={this.state.nextRun} />;
  }

  public render() {
    return <div className="next-run">{this.renderContent()}</div>;
  }
}
