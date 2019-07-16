/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the 'License'); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

import React, { useState, useEffect } from 'react';
import PropTypes from 'prop-types';
import withStyles, { WithStyles } from '@material-ui/core/styles/withStyles';

import Select from 'components/AbstractWidget/SelectWidget';
import FormControlLabel from '@material-ui/core/FormControlLabel';
import Checkbox from '@material-ui/core/Checkbox';
import Paper from '@material-ui/core/Paper';

import ThemeWrapper from 'components/ThemeWrapper';
import If from 'components/If';

const styles = (theme) => {
  return {
    multiCheckboxesContainer: {
      padding: '20px',
    },
    empty: {
      backgroundColor: 'initial',
      padding: 'initial',
      '> h4': {
        marginBottom: '0px',
      },
    },
    checkboxesGroup: {
      display: 'flex',
      'flex-direction': 'column',
    },
    subtitle: {
      color: '#3c4355',
      fontSize: '13px',
      'font-weight': '500',
      marginTop: '15px',
      marginBottom: '5px',
    },
    textWarning: {
      marginBottom: '5px',
    },
    checkbox: {
      width: '45%',
      display: 'inline-flex',
    },
  };
};

interface IJoinTypeWidgetProps extends WithStyles<typeof styles> {
  value: string;
  inputSchema: Array<{ name: string }>;
  onChange: (arg0: string) => void;
}

const DROP_DOWN_OPTIONS: string[] = ['Inner', 'Outer'];

function JoinTypeWidgetView({ value, inputSchema, onChange, classes }) {
  const [joinType, setJoinType] = useState(DROP_DOWN_OPTIONS[1]);
  const [selectedCount, setSelectedCount] = useState(0);
  const [inputs, setInputs] = useState([]);

  const formatOutput = () => {
    const outputArr = inputs.filter((schema) => schema.selected).map((schema) => schema.name);
    onChange(outputArr.join(','));
    setSelectedCount(outputArr.length);
  };

  const joinTypeChange = (event) => {
    setJoinType(event.target.value);
    setInputs(
      inputSchema.map((input) => {
        return { name: input.name, selected: event.target.value === 'Inner' };
      })
    );
  };

  const checkBoxChange = (event) => {
    setInputs(
      inputs.map((input) => {
        if (input.name === event.target.value) {
          input.selected = !input.selected;
        }
        return input;
      })
    );
  };

  useEffect(() => {
    const initialModel = value.split(',').map((input) => input.trim());
    if (!value) {
      setInputs(
        inputSchema.map((input) => {
          return { name: input.name, selected: false };
        })
      );
    }
    if (initialModel.length === inputSchema.length) {
      setJoinType('Inner');
      setInputs(
        inputSchema.map((input) => {
          return { name: input.name, selected: true };
        })
      );
    } else {
      setJoinType('Outer');
      setInputs(
        inputSchema.map((input) => {
          return {
            name: input.name,
            selected: initialModel.indexOf(input.name) !== -1 ? true : false,
          };
        })
      );
    }
  }, []);

  useEffect(
    () => {
      formatOutput();
    },
    [inputs]
  );

  return (
    <React.Fragment>
      <If condition={inputs.length > 0}>
        <Paper className={classes.multiCheckboxesContainer}>
          <Select
            widgetProps={{ values: DROP_DOWN_OPTIONS }}
            value={joinType}
            onChange={joinTypeChange}
          />
          <If condition={joinType === 'Outer'}>
            <div className={classes.checkboxesGroup}>
              <div className={classes.subtitle}>Required Inputs</div>
              <If condition={selectedCount === inputs.length}>
                <div className="text-warning">
                  <span>Setting all stages as required inputs will be treated as Inner Join.</span>
                </div>
              </If>
              <div>
                {inputs &&
                  inputs.map((input) => {
                    return (
                      <FormControlLabel
                        className={classes.checkbox}
                        control={
                          <Checkbox
                            checked={input.selected}
                            value={input.name}
                            color="primary"
                            onChange={checkBoxChange}
                          />
                        }
                        label={input.name}
                      />
                    );
                  })}
              </div>
            </div>
          </If>
        </Paper>
      </If>
      <If condition={inputs.length === 0}>
        <div className={classes.empty}>
          <h4>No input stages</h4>
        </div>
      </If>
    </React.Fragment>
  );
}
const StyledJoinTypeWidget = withStyles(styles)(JoinTypeWidgetView);

export default function JoinTypeWidget(props: IJoinTypeWidgetProps) {
  return (
    <ThemeWrapper>
      <StyledJoinTypeWidget {...props} />
    </ThemeWrapper>
  );
}

(JoinTypeWidget as any).propTypes = {
  value: PropTypes.string,
  inputSchema: PropTypes.object,
  onChange: PropTypes.func,
};
