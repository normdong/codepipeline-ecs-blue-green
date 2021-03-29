# codepipeline-ecs-blue-green
A sample project using AWS CodePipeline + ECS + Blue/Green deployment - that said, currently only the deploy part has been finished.

# Application
The project provides a very simple Java application which prints out the host name and IP address it's running on. For demo purpose only. Application listens on Port 8080 with HTTP.

### Build Application
Build with Maven:
```
mvn spring-boot:build-image
```

This builds a docker image and tags it as `beedog/${project.artifactId}:${project.version}`. This is defined in app/demo/pom.xml.

After image is built, push it to Docker Hub. Currently I have `beedog/codepipeline-ecs-blue-green:0.0.1-SNAPSHOT` and `beedog/codepipeline-ecs-blue-green:0.0.2-SNAPSHOT` pushed to [Docker Hub](https://hub.docker.com/r/beedog/codepipeline-ecs-blue-green).

# CloudFormation
- `network-stack` creates a VPC, 3 public subnets and 3 private subnets in 3 AZs, with associated networking routes in your default region. All subnets have internet access.
  Exports the VPC and subnet IDs.
  ```
  aws cloudformation create-stack --template-body file://network-stack.yml --stack-name network-stack
  ```
- `bluegreen-stack` creates an ALB, associated listeners and target groups used for blue/green deployment. Plus the CodeDeploy application, ECS cluster and ECS task definition.
  ```
  aws cloudformation create-stack --template-body file://bluegreen-stack.yml --capabilities CAPABILITY_IAM --stack-name bluegreen-stack
  ```

# Before we go into the commands below, I'd like to quickly address a question: Why not use AWS::CodeDeploy::BlueGreen?
It looks nice - until you actually use it and discover that it has many problems and restrictions (won't go into details here). It is ironically a good thing to use for a demo project but for all the corners I've cut during building this project, I choose not to use it. I simply do not trust it for production use from my personal experience. Change my mind!

# Create an ECS Service
```
ECSARN=$(aws cloudformation describe-stacks --stack-name bluegreen-stack --query "Stacks[0].Outputs[?OutputKey=='ecsCluster'].OutputValue" --output text)
TDARN=$(aws cloudformation describe-stack-resources --stack-name bluegreen-stack --logical-resource-id taskDefinition --query "StackResources[0]".PhysicalResourceId --output text)
TGARN1=$(aws cloudformation describe-stack-resources --stack-name bluegreen-stack --logical-resource-id tg1 --query "StackResources[0]".PhysicalResourceId --output text)
TGARN2=$(aws cloudformation describe-stack-resources --stack-name bluegreen-stack --logical-resource-id tg2 --query "StackResources[0]".PhysicalResourceId --output text)
SUBNET1=$(aws cloudformation describe-stack-resources --stack-name network-stack --logical-resource-id privateSubnet1 --query "StackResources[0]".PhysicalResourceId --output text)
SUBNET2=$(aws cloudformation describe-stack-resources --stack-name network-stack --logical-resource-id privateSubnet2 --query "StackResources[0]".PhysicalResourceId --output text)
SUBNET3=$(aws cloudformation describe-stack-resources --stack-name network-stack --logical-resource-id privateSubnet3 --query "StackResources[0]".PhysicalResourceId --output text)
SG=$(aws cloudformation describe-stack-resources --stack-name network-stack --logical-resource-id privateSubnetSg --query "StackResources[0]".PhysicalResourceId --output text)
aws ecs create-service --cluster $ECSARN --service-name demo --task-definition $TDARN --load-balancers targetGroupArn=$TGARN1,containerName=demo,containerPort=8080 targetGroupArn=$TGARN2,containerName=demo,containerPort=8080 --desired-count 0 --launch-type FARGATE --platform-version 1.4.0 --network-configuration "awsvpcConfiguration={subnets=[$SUBNET1,$SUBNET2,$SUBNET3],securityGroups=[$SG],assignPublicIp=DISABLED}" --scheduling-strategy REPLICA --deployment-controller type=CODE_DEPLOY --enable-execute-command
```

# Create a CodeDeploy Deployment Group
```
APPNAME=$(aws cloudformation describe-stack-resources --stack-name bluegreen-stack --logical-resource-id codeDeployApplication --query "StackResources[0]".PhysicalResourceId --output text)
CDSRARN=$(aws cloudformation describe-stacks --stack-name bluegreen-stack --query "Stacks[0].Outputs[?OutputKey=='codeDeployServiceRole'].OutputValue" --output text)
TGNAME1=$(aws cloudformation describe-stacks --stack-name bluegreen-stack --query "Stacks[0].Outputs[?OutputKey=='tgName1'].OutputValue" --output text)
TGNAME2=$(aws cloudformation describe-stacks --stack-name bluegreen-stack --query "Stacks[0].Outputs[?OutputKey=='tgName2'].OutputValue" --output text)
LISTENERARN=$(aws cloudformation describe-stack-resources --stack-name bluegreen-stack --logical-resource-id httpListener --query "StackResources[0]".PhysicalResourceId --output text)
ECSCLUSTERNAME=$(aws cloudformation describe-stack-resources --stack-name bluegreen-stack --logical-resource-id ecsCluster --query "StackResources[0]".PhysicalResourceId --output text)
sed -i "s|APPNAME|${APPNAME}|g" create-deployment-group.json
sed -i "s|CDSRARN|${CDSRARN}|g" create-deployment-group.json
sed -i "s|TGNAME1|${TGNAME1}|g" create-deployment-group.json
sed -i "s|TGNAME2|${TGNAME2}|g" create-deployment-group.json
sed -i "s|LISTENERARN|${LISTENERARN}|g" create-deployment-group.json
sed -i "s|ECSCLUSTERNAME|${ECSCLUSTERNAME}|g" create-deployment-group.json
aws deploy create-deployment-group --cli-input-json file://create-deployment-group.json
```

# Deployments
Now the basic infrastructure and components are setup. We can update the service's desired count to run a task.
```
ECSCLUSTERNAME=$(aws cloudformation describe-stack-resources --stack-name bluegreen-stack --logical-resource-id ecsCluster --query "StackResources[0]".PhysicalResourceId --output text)
aws ecs update-service --cluster $ECSCLUSTERNAME --service demo --desired-count 1
```

After a few minutes, the service should be accessible via the ALB's URL.
```
aws cloudformation describe-stacks --stack-name bluegreen-stack --query "Stacks[0].Outputs[?OutputKey=='albdns'].OutputValue" --output text
```

Once a new version is ready to be deployed, update the task definition in the bluegreen-stack to get an updated task definition resource. Then, create a deployment with CodeDeploy.
```
APPNAME=$(aws cloudformation describe-stack-resources --stack-name bluegreen-stack --logical-resource-id codeDeployApplication --query "StackResources[0]".PhysicalResourceId --output text)
TDARN=$(aws cloudformation describe-stack-resources --stack-name bluegreen-stack --logical-resource-id taskDefinition --query "StackResources[0]".PhysicalResourceId --output text)
sed -i "s|APPNAME|${APPNAME}|g" create-deployment.json
sed -i "s|TDARN|${TDARN}|g" create-deployment.json
aws deploy create-deployment --cli-input-json file://create-deployment.json
```

# Clean up
Delete the ECS service
```
ECSCLUSTERNAME=$(aws cloudformation describe-stack-resources --stack-name bluegreen-stack --logical-resource-id ecsCluster --query "StackResources[0]".PhysicalResourceId --output text)
aws ecs update-service --cluster $ECSCLUSTERNAME --service demo --desired-count 0
aws ecs delete-service --cluster $ECSCLUSTERNAME --service demo --force
```

Delete the CloudFormation stacks
```
aws cloudformation delete-stack --stack-name bluegreen-stack
aws cloudformation delete-stack --stack-name network-stack
```

# Potential Improvements & Extensions
For demonstration purpose this project is built light with many cut corners. It is not suitable for production use without improvements and extensions.
- There are a lot of hard coded values. More paramiterization should be applied.
- Manual deploy of each update. Still room for more automation there.
- At the moment there is no persistency layer (i.e. storage, database).
- IAM policies are quite loose, and it assumes that you already have a few service linked roles in your account.
- Application is currently served via HTTP. Need to implement HTTP to HTTPS redirection and SSL, etc.
- Currently when updating the image link in the task definition, a new task definition is created and the old one is deactivated. As a result, this could block rollback. From memory AWS::CodeDeploy::BlueGreen actually handles it pretty well. For this project though, due to the manual labor required for each update, a better solution needs to be implemented. Once it moves to CodePipeline, it should be possible to use a build stage to manipulate the CFN template.
- No performance/traffic metrics are collected/monitored. There is also no scaling yet.
- There are 3 AZs in the VPC. However, there is currently only 1 internet gateway and 1 nat gateway. In the event where that AZ goes down, it is a total outage. You should have a pair of internet gateway and nat gateway in each AZ in a production environment.
- Traffic is served from the container directly and there is no caching from say a proxy server. There is no CDN or edge server either.
- `CodeDeployDefault.ECSAllAtOnce` is used because - it is faster to finish a deployment that way so it decreases my development time. In a production environment you should use batched deployments.