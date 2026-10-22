# Production Deployment Runbook & Infrastructure Guide

This guide details the deployment architecture, infrastructure-as-code (IaC) configuration, and operations runbook for the **URL Shortener with Click Analytics & Traffic Intelligence** service.

---

## 1. Production Architecture Overview

The production topology employs a 3-tier, multi-Availability Zone (Multi-AZ) architecture deployed on AWS:

```
                            [ Public Internet ]
                                     |
                                     v
                       [ AWS Application Load Balancer ]
                        (Public Subnets across 2 AZs)
                                     |
           +-------------------------+-------------------------+
           |                                                   |
           v                                                   v
 [ ECS Fargate Task (AZ-1) ]                         [ ECS Fargate Task (AZ-2) ]
 (Private App Subnet 10.0.32.0/20)                   (Private App Subnet 10.0.48.0/20)
           |                                                   |
           +-------------------------+-------------------------+
                                     |
               +---------------------+---------------------+
               |                                           |
               v                                           v
 [ Amazon RDS PostgreSQL (Multi-AZ) ]        [ Amazon ElastiCache Redis Cluster ]
 (Private Data Subnets 10.0.64.0/20)         (Private Data Subnets 10.0.80.0/20)
```

### Security & Isolation Boundaries
- **Zero Public Compute**: ECS Fargate tasks and database instances reside exclusively in private subnets with no public IPv4 addresses.
- **Outbound Egress**: ECS tasks route through an AWS NAT Gateway for outbound API calls and ECR image pulls.
- **Security Group Strictness**:
  - `ALB Security Group`: Ingress 80/443 from `0.0.0.0/0`.
  - `ECS Security Group`: Ingress 8080 **only** from ALB security group.
  - `RDS Security Group`: Ingress 5432 **only** from ECS security group.
  - `Redis Security Group`: Ingress 6379 **only** from ECS security group.
- **Zero Static AWS Keys**: GitHub Actions authenticates directly to AWS STS using OpenID Connect (OIDC) token exchange.

---

## 2. Infrastructure as Code (Terraform)

Terraform configurations are located in `infra/terraform/`:
- `versions.tf`: Terraform and AWS provider constraints.
- `variables.tf`: Input variables, instance types, and subnet sizing.
- `vpc.tf`: Multi-AZ VPC, public/private subnets, Internet Gateway, and NAT Gateway.
- `security_groups.tf`: Least-privilege firewall definitions.
- `alb.tf`: Application Load Balancer, target group, and health checks.
- `ecs.tf`: ECS Fargate cluster, IAM task execution roles, and service definitions.
- `rds.tf`: Amazon RDS PostgreSQL 15 Multi-AZ instance with gp3 storage.
- `elasticache.tf`: Amazon ElastiCache Redis 7 replication group.
- `outputs.tf`: Exported connection endpoints and DNS identifiers.

### Provisioning Steps

1. **Initialize Terraform Backend**:
   ```bash
   cd infra/terraform
   terraform init
   ```

2. **Validate and Review Plan**:
   ```bash
   terraform plan -out=tfplan
   ```

3. **Apply Infrastructure**:
   ```bash
   terraform apply tfplan
   ```

---

## 3. GitHub Actions Continuous Deployment (OIDC Setup)

### Setting up AWS IAM OIDC Role for GitHub Actions
1. In the AWS IAM Console, add an Identity Provider:
   - Provider Type: `OpenID Connect`
   - Provider URL: `https://token.actions.githubusercontent.com`
   - Audience: `sts.amazonaws.com`
2. Create an IAM Role (e.g., `GitHubActions-URLShortener-DeployRole`) with Trust Relationship:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Effect": "Allow",
         "Principal": {
           "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com"
         },
         "Action": "sts:AssumeRoleWithWebIdentity",
         "Condition": {
           "StringEquals": {
             "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
           },
           "StringLike": {
             "token.actions.githubusercontent.com:sub": "repo:RishiRaj0128/URL-Shortener-With-Click-Analytics:*"
           }
         }
       }
     ]
   }
   ```
3. Attach permissions to push to ECR and update ECS services (`AmazonEC2ContainerRegistryPowerUser` and ECS service update policy).
4. Store the role ARN in repository secrets as `AWS_ROLE_TO_ASSUME`.

---

## 4. Local Deployment with Docker Compose

For local developer testing, the entire cluster (Postgres, Redis, App instances, Prometheus, and Grafana) can be launched with a single command:

```bash
# 1. Copy environment template
cp .env.example .env

# 2. Build and launch all services
docker compose up --build -d

# 3. Check health and cluster logs
docker compose ps
docker compose logs -f app-1
```

Services exposed locally:
- Ingress HTTP Redirect & API: `http://localhost:8080` (or `8081`, `8082`, `8083`)
- Prometheus Metrics & Alerts: `http://localhost:9090`
- Grafana Dashboards: `http://localhost:3000` (credentials: `admin` / `admin`)
- PostgreSQL: `localhost:5432`
- Redis: `localhost:6379`

---

## 5. Zero-Downtime Deployment & Health Checks

### Rolling Update Strategy
- `minimum_healthy_percent = 100`: AWS ECS guarantees that the full complement of existing tasks remains active and healthy while new tasks boot.
- `maximum_percent = 200`: Allows AWS ECS to spin up a complete set of new tasks in parallel.
- **ALB Health Check Gating**:
  - Target Path: `/actuator/health`
  - Health check evaluates database connectivity, Redis connection, and disk availability.
  - The ALB shifts traffic to new task instances only after 2 consecutive successful checks (30s).
  - Old tasks are gracefully deregistered (`deregistration_delay = 30s`) allowing inflight HTTP redirect requests to drain.

---

## 6. Rollback Playbook

If a critical incident or regression occurs after deployment:

1. **ECS Service Rollback**:
   Roll back immediately to the previous known good task definition revision:
   ```bash
   aws ecs update-service \
     --cluster url-shortener-cluster \
     --service url-shortener-service \
     --task-definition url-shortener-task:<PREVIOUS_REVISION>
   ```

2. **Verify Stability**:
   ```bash
   aws ecs wait services-stable \
     --cluster url-shortener-cluster \
     --services url-shortener-service
   ```

3. **Database Schema State**:
   Flyway migrations in `backend/src/main/resources/db/migration/` are strictly additive and backwards-compatible. New columns and indexes do not break older application revisions.
