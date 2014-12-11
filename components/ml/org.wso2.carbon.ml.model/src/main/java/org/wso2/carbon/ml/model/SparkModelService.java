/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.ml.model;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.component.ComponentContext;
import org.wso2.carbon.ml.model.constants.MLModelConstants;
import org.wso2.carbon.ml.model.exceptions.MLAlgorithmConfigurationParserException;
import org.wso2.carbon.ml.model.exceptions.ModelServiceException;
import org.wso2.carbon.ml.model.spark.algorithms.SupervisedModel;

import java.text.DecimalFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @scr.component name="modelService" immediate="true"
 * Service class for machine learning model building related tasks
 */

public class SparkModelService implements ModelService {
    private static final Log logger = LogFactory.getLog(SparkModelService.class);
    private MLAlgorithmConfigurationParser mlAlgorithmConfigurationParser;

    /**
     * ModelService activator
     *
     * @param context ComponentContext
     */
    protected void activate(ComponentContext context) {
        try {
            SparkModelService sparkModelService = new SparkModelService();
            sparkModelService.mlAlgorithmConfigurationParser = new MLAlgorithmConfigurationParser
                    (MLModelConstants.ML_ALGORITHMS_CONFIG_XML);
            context.getBundleContext().registerService(ModelService.class.getName(),
                    sparkModelService, null);
            logger.info("ML Model Service Started");
        } catch (Exception e) {
            logger.error("An error occured while activating model service: " + e
                    .getMessage(), e);
        }
    }

    /**
     * ModelService de-activator
     *
     * @param context ComponentContext
     */
    protected void deactivate(ComponentContext context) {
        logger.info("ML Model Service Stopped");
    }

    /**
     * @param algorithm Name of the machine learning algorithm
     * @return Json array containing hyper parameters
     * @throws org.wso2.carbon.ml.model.exceptions.ModelServiceException
     */
    public JSONArray getHyperParameters(String algorithm) throws ModelServiceException {
        try {
            return this.mlAlgorithmConfigurationParser.getHyperParameters(algorithm);
        } catch (MLAlgorithmConfigurationParserException e) {
            logger.error("An error occurred while retrieving hyper parameters :" + e.getMessage(),
                    e);
            throw new ModelServiceException(e.getMessage(), e);
        }
    }

    /**
     * @param algorithmType Type of the machine learning algorithm - e.g. Classification
     * @return List of algorithm names
     * @throws ModelServiceException
     */
    public List<String> getAlgorithmsByType(String algorithmType) throws ModelServiceException {
        try {
            return this.mlAlgorithmConfigurationParser.getAlgorithms(algorithmType);
        } catch (MLAlgorithmConfigurationParserException e) {
            logger.error("An error occurred while retrieving algorithm names: " + e.getMessage(),
                    e);
            throw new ModelServiceException(e.getMessage(), e);
        }
    }

    /**
     * @param algorithmType    Type of the machine learning algorithm - e.g. Classification
     * @param userResponseJson User's response to a questionnaire about machine learning task
     * @return Map containing names of recommended machine learning algorithms and
     * recommendation scores (out of 5) for each algorithm
     * @throws ModelServiceException
     */
    public Map<String, Double> getRecommendedAlgorithms(String algorithmType,
            String userResponseJson)
            throws ModelServiceException {
        Map<String, Double> recommendations = new HashMap<String, Double>();
        try {
            JSONObject userResponse = new JSONObject(userResponseJson);
            Map<String, List<Integer>> algorithmRatings = this.mlAlgorithmConfigurationParser
                    .getAlgorithmRatings(algorithmType);
            // check whether the response is binary and eliminate logistic regression
            if (MLModelConstants.NO.equals(userResponse.getString(MLModelConstants.BINARY))) {
                algorithmRatings.remove(
                        MLModelConstants.SUPERVISED_ALGORITHM.LOGISTIC_REGRESSION.toString());
            }
            for (Map.Entry<String, List<Integer>> rating : algorithmRatings.entrySet()) {
                if (MLModelConstants.HIGH.equals(
                        userResponse.get(MLModelConstants.INTERPRETABILITY))) {
                    rating.getValue().set(0, rating.getValue().get(0) * 5);
                } else if (MLModelConstants.MEDIUM.equals(
                        userResponse.get(MLModelConstants.INTERPRETABILITY))) {
                    rating.getValue().set(0, rating.getValue().get(0) * 3);
                } else {
                    rating.getValue().set(0, 5);
                }
                if (MLModelConstants.LARGE.equals(
                        userResponse.get(MLModelConstants.DATASET_SIZE))) {
                    rating.getValue().set(1, rating.getValue().get(1) * 5);
                } else if (MLModelConstants.MEDIUM.equals(
                        userResponse.get(MLModelConstants.DATASET_SIZE))) {
                    rating.getValue().set(1, rating.getValue().get(1) * 3);
                } else if (MLModelConstants.SMALL.equals(
                        userResponse.get(MLModelConstants.DATASET_SIZE))) {
                    rating.getValue().set(1, 5);
                }
                if (MLModelConstants.YES.equals(userResponse.get(MLModelConstants.TEXTUAL))) {
                    rating.getValue().set(2, rating.getValue().get(2) * 3);
                } else {
                    rating.getValue().set(2, 5);
                }
            }
            for (Map.Entry<String, List<Integer>> pair : algorithmRatings.entrySet()) {
                recommendations.put(pair.getKey(), MLModelUtils.sum(pair.getValue()));
            }
            Double max = Collections.max(recommendations.values());
            DecimalFormat ratingNumberFormat = new DecimalFormat(MLModelConstants.DECIMAL_FORMAT);
            Double scaledRating;
            for (Map.Entry<String, Double> recommendation : recommendations.entrySet()) {
                scaledRating = ((recommendation.getValue()) / max) * 5;
                scaledRating = Double.valueOf(ratingNumberFormat.format(scaledRating));
                recommendations.put(recommendation.getKey(), scaledRating);
            }
        } catch (MLAlgorithmConfigurationParserException e) {
            logger.error(
                    "An error occurred while retrieving recommended algorithms: " + e.getMessage(),
                    e);
            throw new ModelServiceException(e.getMessage(), e);
        }
        return recommendations;
    }

    /**
     * @param workflowJSON Workflow as a JSON string
     * @throws ModelServiceException
     */
    public void buildModel(String workflowJSON) throws ModelServiceException {
        // temporarily store thread context class loader (tccl)
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        try {
            // switch class loader to JavaSparkContext class's class loader
            Thread.currentThread().setContextClassLoader(JavaSparkContext.class.getClassLoader());
            JSONObject workflow = new JSONObject(workflowJSON);
            String algorithmType = workflow.getString(MLModelConstants.ALGORITHM_TYPE);
            if (MLModelConstants.CLASSIFICATION.equals(
                    algorithmType) || MLModelConstants.NUMERICAL_PREDICTION.equals(algorithmType)) {
                // create a new spark configuration
                SparkConfigurationParser sparkConfigurationParser = new SparkConfigurationParser(
                        MLModelConstants.SPARK_CONFIG_XML);
                SparkConf sparkConf = sparkConfigurationParser.getSparkConf();
                SupervisedModel supervisedModel = new SupervisedModel();
                supervisedModel.buildModel(workflow, sparkConf);
            }
        } catch (Exception e) {
            logger.error(
                    "An error occurred while building machine learning model: " + e.getMessage(),
                    e);
            throw new ModelServiceException(e.getMessage(), e);
        } finally {
            // switch class loader back to tccl
            Thread.currentThread().setContextClassLoader(tccl);
        }
    }

    /**
     * @param modelID Model ID
     * @param <T>     Model summary type
     * @return Model summary object
     * @throws ModelServiceException
     */
    public <T> T getModelSummary(String modelID) throws ModelServiceException {
        T modelSummary = null;
        try {
            DatabaseHandler databaseHandler = new DatabaseHandler();
            modelSummary = databaseHandler.getModelSummary(modelID);
        } catch (Exception e) {
            logger.error("An error occured while retrieving model summay: " + e.getMessage(), e);
        }
        return modelSummary;
    }

    /**
     * This method checks whether the model building process is completed for a given model id
     * @param modelId
     * @return
     * @throws ModelServiceException
     */
    public boolean isExecutionCompleted(String modelId) throws ModelServiceException {
        try{
            DatabaseHandler handler = new DatabaseHandler();
            return handler.getModelExecutionEndTime(modelId) > 0;
        }catch (Exception e){
            throw  new ModelServiceException("An error has occurred while querying model: " + modelId +
                    " for execution end time: "+ e.getMessage(), e);
        }
    }

    /**
     * This method checks whether the model building process is started for a given model id
     * @param modelId
     * @return
     * @throws ModelServiceException
     */
    public boolean isExecutionStarted(String modelId) throws ModelServiceException {
        try {
            DatabaseHandler handler = new DatabaseHandler();
            return  handler.getModelExecutionStartTime(modelId) > 0;
        }catch (Exception e){
            throw  new ModelServiceException("An error has occurred while querying model: " + modelId +
                    " for execution start time: "+ e.getMessage(), e);
        }
    }
}
