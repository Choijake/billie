provider "aws" {
  region = "ap-northeast-2"
}

# 1. VPC & 네트워크 설정
resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  tags = {
    Name = "PerfTest-VPC"
  }
}

resource "aws_internet_gateway" "igw" {
  vpc_id = aws_vpc.main.id
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.0.1.0/24"
  availability_zone       = "ap-northeast-2a"
  map_public_ip_on_launch = true
}

resource "aws_route_table" "public_rt" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.igw.id
  }
}

resource "aws_route_table_association" "a" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public_rt.id
}

# 2. 보안 그룹
resource "aws_security_group" "sg" {
  name        = "PerfTest-SG"
  description = "Allow SSH, Web, Monitoring, and DB Traffic"
  vpc_id      = aws_vpc.main.id

  # 1. SSH 접속 허용
  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # 2. 웹 서버
  ingress {
    description = "Web Service"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # 3. 모니터링 도구
  ingress {
    description = "Grafana Dashboard"
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "Prometheus UI"
    from_port   = 9090
    to_port     = 9090
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "MySQL Exporter"
    from_port   = 9104
    to_port     = 9104
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # 4. DB 및 미들웨어 포트 허용 (Public IP 접속용)
  ingress {
    description = "MySQL"
    from_port   = 3306
    to_port     = 3306
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "Redis"
    from_port   = 6379
    to_port     = 6379
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "MongoDB"
    from_port   = 27017
    to_port     = 27017
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "RabbitMQ"
    from_port   = 5672
    to_port     = 5672
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "RabbitMQ Management"
    from_port   = 15672
    to_port     = 15672
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "Elasticsearch"
    from_port   = 9200
    to_port     = 9200
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # 5. 내부 통신 전체 허용 (Fallback)
  ingress {
    description = "Allow internal traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
  }

  # 아웃바운드 전체 허용
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# 3. 서버(EC2) 정의
variable "ami_id" {
  default = "ami-04c56ae86baf007f1"
}

resource "aws_instance" "db_server" {
  ami                    = var.ami_id
  instance_type          = "t3.large"
  key_name               = "performance-test-key"
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.sg.id]
  tags = {
    Name = "NextDoor-DB"
  }
}

resource "aws_instance" "app_server" {
  ami                    = var.ami_id
  instance_type          = "t3.medium"
  key_name               = "performance-test-key"
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.sg.id]
  tags = {
    Name = "NextDoor-App"
  }
}

resource "aws_instance" "util_server" {
  ami                    = var.ami_id
  instance_type          = "t3.large"
  key_name               = "performance-test-key"
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.sg.id]
  tags = {
    Name = "NextDoor-Util"
  }
}

# 4. 출력 변수
output "db_ip" {
  value = aws_instance.db_server.public_ip
}
output "app_ip" {
  value = aws_instance.app_server.public_ip
}
output "util_ip" {
  value = aws_instance.util_server.public_ip
}
output "util_priv_ip" {
  value = aws_instance.util_server.private_ip
}