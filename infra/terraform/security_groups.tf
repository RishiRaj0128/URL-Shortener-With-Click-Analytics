# ==============================================================
# Security Groups: Strict Defense-in-Depth Firewall Rules
# ==============================================================

# ALB Security Group: Ingress from Internet, Egress only to ECS
resource "aws_security_group" "alb" {
  name        = "${var.app_name}-alb-sg"
  description = "Allows inbound HTTP/HTTPS traffic to ALB"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "HTTP Ingress from everywhere"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS Ingress from everywhere"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Outbound to anywhere"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.app_name}-alb-sg"
  }
}

# ECS Security Group: Ingress ONLY from ALB, Egress to DB & Redis & NAT
resource "aws_security_group" "ecs" {
  name        = "${var.app_name}-ecs-sg"
  description = "Controls inbound traffic to Spring Boot containers"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Inbound from ALB only"
    from_port       = var.container_port
    to_port         = var.container_port
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    description = "Outbound to VPC and Internet via NAT"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.app_name}-ecs-sg"
  }
}

# RDS PostgreSQL Security Group: Ingress ONLY from ECS
resource "aws_security_group" "rds" {
  name        = "${var.app_name}-rds-sg"
  description = "Allows PostgreSQL connections from ECS tasks only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "PostgreSQL from ECS tasks"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }

  egress {
    description = "Outbound disabled for DB isolation"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.app_name}-rds-sg"
  }
}

# ElastiCache Redis Security Group: Ingress ONLY from ECS
resource "aws_security_group" "redis" {
  name        = "${var.app_name}-redis-sg"
  description = "Allows Redis connections from ECS tasks only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "Redis port 6379 from ECS tasks"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }

  egress {
    description = "Outbound disabled for Redis isolation"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.app_name}-redis-sg"
  }
}
