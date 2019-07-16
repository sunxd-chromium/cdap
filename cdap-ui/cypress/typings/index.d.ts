export {}
declare global {
  namespace Cypress {
    // tslint:disable-next-line: interface-name
    interface Chainable {
      /**
       * Uploads a pipeline json from fixtures to input file element.
       *
       * @fileName - Name of the file from fixture folder including extension.
       * @selector - css selector to query for the input[type="file"] element.
      */
      upload_pipeline: (filename: string, selector: string) => Chainable<JQuery<HTMLElement>>;
      /**
       * Cleans up pipelines once expecuting a specific test. This is to remove state
       *  from individual tests.
       *
       * @headers - Any request headers to be passed.
       * @pipelineName - name of the pipeline to be deleted.
      */
      cleanup_pipelines: (headers: any, pipelineName: string) => Chainable<Request>;

      /**
       * Command to check if wrangler is up and running and if not starts wrangler
       * before running any wrangler related tests.
       */
      start_wrangler: (headers: any) => Chainable<any>;

      /**
       * Fills up the create connection form for GCS
       */

      fill_GCS_connection_create_form: (connectionId: string, projectId?: string, serviceAccountPath?: string) => void;

      /**
       * Test GCS connection
       */
      test_GCS_connection: (connectionId: string, projectId?: string, serviceAccountPath?: string) => void;

      /**
       * Creates a GCS connection
       */
      create_GCS_connection: (connectionId: string, projectId?: string, serviceAccountPath?: string) => void;


      /**
      * Fills up the create connection form for BIGQUERY
      */

      fill_BIGQUERY_connection_create_form: (connectionId: string, projectId?: string, serviceAccountPath?: string) => void;

      /**
       * Test BIGQUERY connection
       */
      test_BIGQUERY_connection: (connectionId: string, projectId?: string, serviceAccountPath?: string) => void;

      /**
       * Creates a BigQuery connection
       */

      create_BIGQUERY_connection: (connectionId: string, projectId?: string, serviceAccountPath?: string) => void;


      /**
      * Fills up the create connection form for BIGQUERY
      */

      fill_SPANNER_connection_create_form: (connectionId: string, projectId?: string, serviceAccountPath?: string) => void;

      /**
       * Test BIGQUERY connection
       */
      test_SPANNER_connection: (connectionId: string, projectId?: string, serviceAccountPath?: string) => void;

      /**
       * Creates a BigQuery connection
       */

      create_SPANNER_connection: (connectionId: string, projectId?: string, serviceAccountPath?: string) => void;
    }
    // tslint:disable-next-line: interface-name
    interface Window {
      File: any;
      DataTransfer: any;
      sessionStorage: any;
      onbeforeunload: any;
    }
  }
}
