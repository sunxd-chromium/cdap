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

import React from 'react';
import FllHeader from 'components/FieldLevelLineage/v2/FllHeader';
import FllTable from 'components/FieldLevelLineage/v2/FllTable';
import { ITableFields, ILink } from 'components/FieldLevelLineage/v2/Context/FllContextHelper';
import withStyles, { StyleRules } from '@material-ui/core/styles/withStyles';
import { Consumer, FllContext } from 'components/FieldLevelLineage/v2/Context/FllContext';
import * as d3 from 'd3';
import debounce from 'lodash/debounce';
import { grey, orange } from 'components/ThemeWrapper/colors';
import If from 'components/If';

const styles = (): StyleRules => {
  return {
    root: {
      paddingLeft: '100px',
      paddingRight: '100px',
      display: 'flex',
      justifyContent: 'space-between',
      position: 'relative',
    },
    container: {
      position: 'absolute',
      height: '110%',
      width: '100%',
      pointerEvents: 'none',
      overflow: 'visible',
    },
  };
};

interface ILineageState {
  activeField: string;
  activeCauseSets: ITableFields;
  activeImpactSets: ITableFields;
  activeLinks: ILink[];
}

class LineageSummary extends React.Component<{ classes }, ILineageState> {
  // TO DO: This currently breaks when the window is scrolled before drawing
  private drawLineFromLink({ source, destination }, isSelected = false) {
    // get source and destination elements and their coordinates
    const sourceId = source.id.replace(/\./g, '\\.');
    const destId = destination.id.replace(/\./g, '\\.');

    const sourceEl = d3.select(`#${sourceId}`);
    const destEl = d3.select(`#${destId}`);

    const offsetX = -100; // From the padding on the LineageSummary
    const offsetY = -48 + window.pageYOffset; // From the FllHeader

    const sourceXY = sourceEl.node().getBoundingClientRect();
    const destXY = destEl.node().getBoundingClientRect();

    const sourceX1 = sourceXY.right + offsetX;
    const sourceY1 = sourceXY.top + offsetY + 0.5 * sourceXY.height;
    const sourceX2 = destXY.left + offsetX;
    const sourceY2 = destXY.top + offsetY + 0.5 * sourceXY.height;

    // draw an edge from line start to line end
    const linkContainer = isSelected
      ? d3.select('#selected-links')
      : d3.select(`#${sourceId}_${destId}`);

    const third = (sourceX2 - sourceX1) / 3;

    // Draw a line with a bit of curve between the straight parts
    const lineGenerator = d3.line().curve(d3.curveMonotoneX);

    const points = [
      [sourceX1, sourceY1],
      [sourceX1 + third, sourceY1],
      [sourceX2 - third, sourceY2],
      [sourceX2, sourceY2],
    ];

    const edgeColor = isSelected ? orange[50] : grey[300];

    linkContainer
      .append('path')
      .style('stroke', edgeColor)
      .style('stroke-width', '1')
      .style('fill', 'none')
      .attr('d', lineGenerator(points));

    // draw left anchor
    const anchorHeight = 8;
    const anchorRx = 1.8;
    linkContainer
      .append('rect')
      .attr('x', sourceX1 - anchorHeight * 0.5)
      .attr('y', sourceY1 - anchorHeight * 0.5)
      .attr('width', anchorHeight)
      .attr('height', anchorHeight)
      .attr('rx', anchorRx)
      .attr('pointer-events', 'fill') // To make rect clickable
      .style('fill', edgeColor);

    // draw right anchor
    linkContainer
      .append('rect')
      .attr('x', sourceX2 - anchorHeight * 0.5)
      .attr('y', sourceY2 - anchorHeight * 0.5)
      .attr('width', anchorHeight)
      .attr('height', anchorHeight)
      .attr('rx', anchorRx)
      .attr('pointer-events', 'fill') // To make rect clickable
      .style('fill', edgeColor);
  }

  private clearCanvas() {
    // clear any existing links and anchors
    d3.select('#links-container')
      .selectAll('path,rect')
      .remove();
  }

  // Draws only active links
  private drawActiveLinks(activeLinks) {
    this.clearCanvas();

    activeLinks.forEach((link) => {
      this.drawLineFromLink(link, true);
    });
  }

  private drawLinks(links: ILink[], activeFieldId: string = null) {
    if (links.length === 0) {
      return;
    }
    this.clearCanvas();

    links.forEach((link) => {
      const isSelected = link.source.id === activeFieldId || link.destination.id === activeFieldId;
      this.drawLineFromLink(link, isSelected);
    });
  }

  public componentDidUpdate() {
    const { showingOneField, links, activeLinks, activeField } = this.context;
    // if user has just clicked "View Cause and Impact"
    if (showingOneField) {
      this.clearCanvas();
      this.drawActiveLinks(activeLinks);
      return;
    }

    if (activeField) {
      d3.select(`#${activeField}`).classed('selected', true);
    }

    this.drawLinks(links, activeField);
  }

  public componentWillUnmount() {
    window.removeEventListener('resize', debounce(this.drawLinks.bind(this), 1));
  }

  public componentDidMount() {
    const { links, activeField } = this.context;

    this.drawLinks(links, activeField);
    window.addEventListener('resize', debounce(this.drawLinks.bind(this, links, activeField), 1));
  }
  public render() {
    return (
      <Consumer>
        {({
          causeSets,
          target,
          targetFields,
          impactSets,
          links,
          activeLinks,
          activeCauseSets,
          activeImpactSets,
          showingOneField,
        }) => {
          let visibleLinks = links;
          let visibleCauseSets = causeSets;
          let visibleImpactSets = impactSets;

          if (showingOneField) {
            visibleLinks = activeLinks;
            visibleCauseSets = activeCauseSets;
            visibleImpactSets = activeImpactSets;
          }

          return (
            <div className={this.props.classes.root} id="fll-container">
              <svg id="links-container" className={this.props.classes.container}>
                <g>
                  {visibleLinks.map((link) => {
                    const id = `${link.source.id}_${link.destination.id}`;
                    return <svg id={id} key={id} className="fll-link" />;
                  })}
                </g>
                <g id="selected-links" />
              </svg>
              <div>
                <FllHeader type="cause" total={Object.keys(visibleCauseSets).length} />
                <If condition={Object.keys(visibleCauseSets).length === 0}>
                  <FllTable type="cause" />
                </If>
                {Object.entries(visibleCauseSets).map(([tableId, fields]) => {
                  return <FllTable key={tableId} tableId={tableId} fields={fields} type="cause" />;
                })}
              </div>
              <div>
                <FllHeader type="target" total={Object.keys(targetFields).length} />
                <FllTable tableId={target} fields={targetFields} type="target" />
              </div>
              <div>
                <FllHeader type="impact" total={Object.keys(visibleImpactSets).length} />
                <If condition={Object.keys(visibleImpactSets).length === 0}>
                  <FllTable type="impact" />
                </If>
                {Object.entries(visibleImpactSets).map(([tableId, fields]) => {
                  return <FllTable key={tableId} tableId={tableId} fields={fields} type="impact" />;
                })}
              </div>
            </div>
          );
        }}
      </Consumer>
    );
  }
}

LineageSummary.contextType = FllContext;

const StyledLineageSummary = withStyles(styles)(LineageSummary);

export default StyledLineageSummary;
